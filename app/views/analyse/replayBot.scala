package views.html.analyse

import lila.app.templating.Environment.{ *, given }
import lila.app.ui.ScalatagsTemplate.*
import lila.game.Pov

object replayBot:

  def apply(
      pov: Pov,
      initialFen: Option[chess.format.Fen.Epd],
      pgn: String,
      simul: Option[lila.simul.Simul],
      cross: Option[lila.game.Crosstable.WithMatchup]
  )(using PageContext) =
    views.html.base.layout(
      title = replay.titleOf(pov),
      moreCss = cssTag("analyse.round"),
      openGraph = povOpenGraph(pov).some,
      csp = bits.csp,
      robots = false
    ):
      main(cls := "analyse")(
        st.aside(cls := "analyse__side")(
          views.html.game.side(pov, initialFen, none, simul = simul, bookmarked = false)
        ),
        div(cls := "analyse__board main-board")(chessgroundBoard),
        div(cls := "analyse__tools")(div(cls := "ceval")),
        div(cls := "analyse__controls"),
        div(cls := "analyse__underboard")(
          div(cls := "analyse__underboard__panels")(
            div(cls := "fen-pgn active")(
              div(
                strong("FEN"),
                input(readonly, spellcheck := false, cls := "copyable autoselect analyse__underboard__fen")
              ),
              div(cls := "pgn")(pgn)
            ),
            cross.map: c =>
              div(cls := "ctable active")(
                views.html.game.crosstable(pov.player.userId.fold(c)(c.fromPov), pov.gameId.some)
              )
          )
        )
      )
