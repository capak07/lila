package lila.chat

import chess.{ Color, PlayerTitle }

import lila.user.User

sealed trait Line:
  def text: String
  def author: String
  def deleted: Boolean
  def isSystem    = author == User.lichessName.value
  def isHuman     = !isSystem
  def humanAuthor = isHuman.option(author)
  def troll: Boolean
  def flair: Boolean
  def userIdMaybe: Option[UserId]

case class UserLine(
    username: UserName,
    title: Option[PlayerTitle],
    patron: Boolean,
    flair: Boolean,
    text: String,
    troll: Boolean,
    deleted: Boolean
) extends Line:

  def author = username.value

  def userId      = username.id
  def userIdMaybe = userId.some

  def delete = copy(deleted = true)

  def isVisible = !troll && !deleted

  def isLichess = userId.is(User.lichessId)

case class PlayerLine(color: Color, text: String) extends Line:
  def deleted     = false
  def author      = color.name
  def troll       = false
  def flair       = false
  def userIdMaybe = none

object Line:

  val textMaxSize = 140
  val titleSep    = '~'

  import reactivemongo.api.bson.*

  private val invalidLine =
    UserLine(UserName(""), None, false, false, "[invalid character]", troll = false, deleted = true)

  private[chat] given BSONHandler[UserLine] = BSONStringHandler.as[UserLine](
    v => strToUserLine(v).getOrElse(invalidLine),
    userLineToStr
  )

  private[chat] given BSONHandler[Line] = BSONStringHandler.as[Line](
    v => strToLine(v).getOrElse(invalidLine),
    lineToStr
  )

  private val trollChar       = "!"
  private val deletedChar     = "?"
  private val patronChar      = "&"
  private val flairChar       = ":"
  private val patronFlairChar = ";"
  private val UserLineRegex = {
    """(?s)([\w-~]{2,}+)([ """ + s"$trollChar$deletedChar$patronChar$flairChar$patronFlairChar" + """])(.++)"""
  }.r
  private def strToUserLine(str: String): Option[UserLine] = str match
    case UserLineRegex(username, sep, text) =>
      val troll   = sep == trollChar
      val deleted = sep == deletedChar
      val patron  = sep == patronChar || sep == patronFlairChar
      val flair   = sep == flairChar || sep == patronFlairChar
      val (title, name) = username.split(titleSep) match
        case Array(title, name) => (PlayerTitle.get(title), UserName(name))
        case _                  => (none, UserName(username))
      UserLine(name, title, patron, flair, text, troll = troll, deleted = deleted).some
    case _ => none
  def userLineToStr(x: UserLine): String =
    val sep =
      if x.troll then trollChar
      else if x.deleted then deletedChar
      else if x.patron then if x.flair then patronFlairChar else patronChar
      else if x.flair then flairChar
      else " "
    val tit = x.title.so(_.value + titleSep)
    s"$tit${x.username}$sep${x.text}"

  def strToLine(str: String): Option[Line] =
    strToUserLine(str).orElse {
      str.headOption.flatMap(Color.apply).map { color =>
        PlayerLine(color, str.drop(2))
      }
    }
  def lineToStr(x: Line) =
    x match
      case u: UserLine   => userLineToStr(u)
      case p: PlayerLine => s"${p.color.letter} ${p.text}"
