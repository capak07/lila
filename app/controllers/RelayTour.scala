package controllers

import play.api.mvc.*
import views.*

import lila.app.{ *, given }
import lila.common.config.{ Max, MaxPerSecond }
import lila.common.{ IpAddress, config }
import lila.relay.RelayTour as TourModel

final class RelayTour(env: Env, apiC: => Api) extends LilaController(env):

  def index(page: Int, q: String) = Open:
    indexResults(page, q)

  def indexLang = LangPage(routes.RelayTour.index())(indexResults(1, ""))

  private def indexResults(page: Int, q: String)(using ctx: Context) =
    Reasonable(page, config.Max(20)):
      q.trim.take(100).some.filter(_.nonEmpty) match
        case Some(query) =>
          env.relay.pager
            .search(query, page)
            .flatMap: pager =>
              Ok.pageAsync:
                html.relay.tour.search(pager, query)
        case None =>
          for
            active   <- (page == 1).so(env.relay.listing.active.get({}))
            upcoming <- (page == 1).so(env.relay.listing.upcoming.get({}))
            past     <- env.relay.pager.inactive(page)
            render <- renderAsync:
              html.relay.tour.index(active, upcoming, past)
          yield Ok(render)

  def calendar = page("broadcast-calendar", "calendar")
  def help     = page("broadcasts", "help")

  def by(owner: UserStr, page: Int) = Open:
    Reasonable(page, config.Max(20)):
      FoundPage(env.user.lightUser(owner.id)): owner =>
        env.relay.pager
          .byOwner(owner.id, page)
          .map:
            html.relay.tour.byOwner(_, owner)

  def subscribed(page: Int) = Auth { ctx ?=> me ?=>
    Reasonable(page, config.Max(20)):
      env.relay.pager
        .subscribedBy(me.userId, page)
        .flatMap: pager =>
          Ok.pageAsync:
            html.relay.tour.subscribed(pager)
  }

  private def page(key: String, menu: String) = Open:
    pageHit
    FoundPage(env.api.cmsRender(lila.cms.CmsPage.Key(key))): p =>
      html.relay.tour.page(p, menu)

  def form = Auth { ctx ?=> _ ?=>
    NoLameOrBot:
      Ok.page(html.relay.tourForm.create(env.relay.tourForm.create))
  }

  def create = AuthOrScopedBody(_.Study.Write) { ctx ?=> me ?=>
    NoLameOrBot:
      def whenRateLimited = negotiate(Redirect(routes.RelayTour.index()), rateLimited)
      env.relay.tourForm.create
        .bindFromRequest()
        .fold(
          err =>
            negotiate(
              BadRequest.page(html.relay.tourForm.create(err)),
              jsonFormError(err)
            ),
          setup =>
            rateLimitCreation(whenRateLimited):
              env.relay.api.tourCreate(setup).flatMap { tour =>
                negotiate(
                  Redirect(routes.RelayRound.form(tour.id)).flashSuccess,
                  JsonOk(env.relay.jsonView(tour.withRounds(Nil), withUrls = true))
                )
              }
        )
  }

  def edit(id: TourModel.Id) = Auth { ctx ?=> _ ?=>
    WithTourCanUpdate(id): tg =>
      Ok.page:
        html.relay.tourForm.edit(tg, env.relay.tourForm.edit(tg))
  }

  def update(id: TourModel.Id) = AuthOrScopedBody(_.Study.Write) { ctx ?=> me ?=>
    WithTourCanUpdate(id): tg =>
      env.relay.tourForm
        .edit(tg)
        .bindFromRequest()
        .fold(
          err =>
            negotiate(
              BadRequest.page(html.relay.tourForm.edit(tg, err)),
              jsonFormError(err)
            ),
          setup =>
            env.relay.api.tourUpdate(tg.tour, setup) >>
              negotiate(
                Redirect(routes.RelayTour.show(tg.tour.slug, tg.tour.id)),
                jsonOkResult
              )
        )
  }

  def delete(id: TourModel.Id) = AuthOrScoped(_.Study.Write) { _ ?=> me ?=>
    WithTour(id): tour =>
      env.relay.api.deleteTourIfOwner(tour).inject(Redirect(routes.RelayTour.by(me.username)).flashSuccess)
  }

  private val ImageRateLimitPerIp = lila.memo.RateLimit.composite[lila.common.IpAddress](
    key = "relay.image.ip"
  )(
    ("fast", 10, 2.minutes),
    ("slow", 60, 1.day)
  )

  def image(id: TourModel.Id) = AuthBody(parse.multipartFormData) { ctx ?=> me ?=>
    WithTourCanUpdate(id): tg =>
      ctx.body.body.file("image") match
        case Some(image) =>
          ImageRateLimitPerIp(ctx.ip, rateLimited):
            (env.relay.api.image.upload(me, tg.tour, image) >> {
              Ok
            }).recover { case e: Exception =>
              BadRequest(e.getMessage)
            }
        case None => env.relay.api.image.delete(tg.tour) >> Ok
  }

  def leaderboardView(id: TourModel.Id) = Open:
    WithTour(id): tour =>
      tour.autoLeaderboard.so(env.relay.leaderboard(tour)).map(_.fold(notFoundJson())(JsonStrOk))

  def subscribe(id: TourModel.Id, isSubscribed: Boolean) = Auth { _ ?=> me ?=>
    env.relay.api.subscribe(id, me.userId, isSubscribed).inject(jsonOkResult)
  }

  def cloneTour(id: TourModel.Id) = Secure(_.Relay) { _ ?=> me ?=>
    WithTour(id): from =>
      env.relay.api
        .cloneTour(from)
        .map: tour =>
          Redirect(routes.RelayTour.edit(tour.id)).flashSuccess
  }

  def show(slug: String, id: TourModel.Id) = Open:
    Found(env.relay.api.tourById(id)): tour =>
      env.relay.listing.defaultRoundToShow
        .get(tour.id)
        .flatMap:
          case None =>
            ctx.me
              .soUse(env.relay.api.canUpdate(tour))
              .flatMap:
                if _ then Redirect(routes.RelayRound.form(tour.id))
                else
                  for
                    owner <- env.user.lightUser(tour.ownerId)
                    markup = tour.markup.map(env.relay.markup(tour))
                    page <- Ok.page(html.relay.tour.showEmpty(tour, owner, markup))
                  yield page
          case Some(round) => Redirect(round.withTour(tour).path)

  def apiShow(id: TourModel.Id) = Open:
    Found(env.relay.api.tourById(id)): tour =>
      env.relay.api
        .withRounds(tour)
        .map: trs =>
          Ok(env.relay.jsonView(trs, withUrls = true))

  def pgn(id: TourModel.Id) = OpenOrScoped(): ctx ?=>
    Found(env.relay.api.tourById(id)): tour =>
      val canViewPrivate = ctx.isWebAuth || ctx.scopes.has(_.Study.Read)
      apiC.GlobalConcurrencyLimitPerIP.download(req.ipAddress)(
        env.relay.pgnStream.exportFullTourAs(tour, ctx.me.ifTrue(canViewPrivate))
      ): source =>
        asAttachmentStream(s"${env.relay.pgnStream.filename(tour)}.pgn"):
          Ok.chunked(source).as(pgnContentType)

  def apiIndex = Anon:
    apiC.jsonDownload:
      env.relay.tourStream
        .officialTourStream(MaxPerSecond(20), Max(getInt("nb") | 20).atMost(100))

  private def WithTour(id: TourModel.Id)(f: TourModel => Fu[Result])(using Context): Fu[Result] =
    Found(env.relay.api.tourById(id))(f)

  private def WithTourCanUpdate(
      id: TourModel.Id
  )(f: TourModel.WithGroupTours => Fu[Result])(using ctx: Context): Fu[Result] =
    WithTour(id): tour =>
      ctx.me
        .soUse { env.relay.api.canUpdate(tour) }
        .elseNotFound:
          env.relay.api.withTours.addTo(tour).flatMap(f)

  private val CreateLimitPerUser = lila.memo.RateLimit[UserId](
    credits = 10 * 10,
    duration = 24.hour,
    key = "broadcast.tournament.user"
  )

  private val CreateLimitPerIP = lila.memo.RateLimit[IpAddress](
    credits = 10 * 10,
    duration = 24.hour,
    key = "broadcast.tournament.ip"
  )

  private[controllers] def rateLimitCreation(
      fail: => Fu[Result]
  )(create: => Fu[Result])(using req: RequestHeader, me: Me): Fu[Result] =
    val cost =
      if isGranted(_.StudyAdmin) then 1
      else if isGranted(_.Relay) then 2
      else if me.hasTitle || me.isVerified then 5
      else 10
    CreateLimitPerUser(me, fail, cost = cost):
      CreateLimitPerIP(req.ipAddress, fail, cost = cost):
        create
