package lila.study

import chess.format.pgn.Tags
import chess.format.{ Fen, Uci }
import chess.{ ByColor, Centis, Color, Elo, FideId, Outcome, PlayerName, PlayerTitle }
import play.api.libs.json.*
import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.fide.Federation

case class ChapterPreview(
    id: StudyChapterId,
    name: StudyChapterName,
    players: Option[ChapterPreview.Players],
    orientation: Color,
    fen: Fen.Epd,
    lastMove: Option[Uci],
    lastMoveAt: Option[Instant],
    /* None = No Result PGN tag, the chapter may not be a game
     * Some(None) = Result PGN tag is "*", the game is ongoing
     * Some(Some(Outcome)) = Game is over with a result
     */
    result: Option[Option[Outcome]]
):
  def finished              = result.exists(_.isDefined)
  def thinkTime             = (!finished).so(lastMoveAt.map(at => (nowSeconds - at.toSeconds).toInt))
  def fideIds: List[FideId] = players.so(_.mapList(_.fideId)).flatten

final class ChapterPreviewApi(
    chapterRepo: ChapterRepo,
    fidePlayerApi: lila.fide.FidePlayerApi,
    cacheApi: lila.memo.CacheApi
)(using Executor):

  import ChapterPreview.AsJsons
  import ChapterPreview.bson.{ projection, given }

  object jsonList:
    // Can't be higher without skewing the clocks
    // because of Preview.secondsSinceLastMove
    private val cacheDuration = 1 second
    private[ChapterPreviewApi] val cache =
      cacheApi[StudyId, AsJsons](256, "study.chapterPreview.json"):
        _.expireAfterWrite(cacheDuration).buildAsyncFuture: studyId =>
          for
            chapters    <- listAll(studyId)
            federations <- fidePlayerApi.federationsOf(chapters.flatMap(_.fideIds))
          yield ChapterPreview.json.write(chapters)(using federations)

    def apply(studyId: StudyId): Fu[AsJsons] = cache.get(studyId)

  object dataList:
    private[ChapterPreviewApi] val cache =
      cacheApi[StudyId, List[ChapterPreview]](256, "study.chapterPreview.data"):
        _.expireAfterWrite(1 minute).buildAsyncFuture(listAll)

    def apply(studyId: StudyId): Fu[List[ChapterPreview]] = cache.get(studyId)

  def firstId(studyId: StudyId): Fu[Option[StudyChapterId]] =
    jsonList(studyId).map(ChapterPreview.json.readFirstId)

  private def listAll(studyId: StudyId): Fu[List[ChapterPreview]] =
    chapterRepo.coll:
      _.find(chapterRepo.$studyId(studyId), projection.some)
        .sort(chapterRepo.$sortOrder)
        .cursor[ChapterPreview]()
        .listAll()

  def invalidate(studyId: StudyId): Unit =
    jsonList.cache.synchronous().invalidate(studyId)
    dataList.cache.synchronous().invalidate(studyId)

object ChapterPreview:

  type Players = ByColor[Player]
  type AsJsons = JsValue

  case class Player(
      name: PlayerName,
      title: Option[PlayerTitle],
      rating: Option[Elo],
      clock: Option[Centis],
      fideId: Option[FideId],
      team: Option[String]
  )

  def players(clocks: Chapter.BothClocks)(tags: Tags): Option[Players] =
    val names = tags.names
    Option.when(names.exists(_.isDefined)):
      (names, tags.fideIds, tags.titles, tags.elos, tags.teams, clocks).mapN: (n, f, t, e, te, c) =>
        Player(n | PlayerName("Unknown player"), t, e, c, f, te)

  object json:
    import lila.common.Json.{ given }

    def readFirstId(js: AsJsons): Option[StudyChapterId] = for
      arr <- js.asOpt[JsArray]
      obj <- arr.value.headOption
      id  <- obj.get[StudyChapterId]("id")
    yield id

    def write(chapters: List[ChapterPreview])(using Federation.ByFideIds): AsJsons =
      given OWrites[ChapterPreview] = writesWithFederations
      Json.toJson(chapters)

    private def playerWithFederations(p: ChapterPreview.Player)(using federations: Federation.ByFideIds) =
      Json
        .obj("name" -> p.name)
        .add("title" -> p.title)
        .add("rating" -> p.rating)
        .add("clock" -> p.clock)
        .add("fed" -> p.fideId.flatMap(federations.get))

    private def writesWithFederations(using Federation.ByFideIds): OWrites[ChapterPreview] = c =>
      Json
        .obj(
          "id"   -> c.id,
          "name" -> c.name
        )
        .add("fen", Option.when(!c.fen.isInitial)(c.fen))
        .add("players", c.players.map(_.mapList(playerWithFederations)))
        .add("orientation", c.orientation.some.filter(_.black))
        .add("lastMove", c.lastMove)
        .add("thinkTime", c.thinkTime)
        .add("status", c.result.map(o => Outcome.showResult(o).replace("1/2", "½")))

  object bson:
    import BSONHandlers.given

    val projection = $doc(
      "name"        -> true,
      "denorm"      -> true,
      "tags"        -> true,
      "lastMoveAt"  -> "$relay.lastMoveAt",
      "orientation" -> "$setup.orientation",
      "rootFen"     -> "$root._.f"
    )

    given BSONDocumentReader[ChapterPreview] = BSONDocumentReader.option[ChapterPreview]: doc =>
      for
        id   <- doc.getAsOpt[StudyChapterId]("_id")
        name <- doc.getAsOpt[StudyChapterName]("name")
        lastMoveAt  = doc.getAsOpt[Instant]("lastMoveAt")
        lastPos     = doc.getAsOpt[Chapter.LastPosDenorm]("denorm")
        tags        = doc.getAsOpt[Tags]("tags")
        orientation = doc.getAsOpt[Color]("orientation") | Color.White
      yield ChapterPreview(
        id = id,
        name = name,
        players = tags.flatMap(ChapterPreview.players(lastPos.so(_.clocks))),
        orientation = orientation,
        fen = lastPos.map(_.fen).orElse(doc.getAsOpt[Fen.Epd]("rootFen")).getOrElse(Fen.initial),
        lastMove = lastPos.flatMap(_.uci),
        lastMoveAt = lastMoveAt,
        result = tags.flatMap(_(_.Result)).map(Outcome.fromResult)
      )
