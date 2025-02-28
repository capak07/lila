package lila.api

import ornicar.scalalib.ThreadLocalRandom
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.StandaloneWSClient

final class InfluxEvent(
    ws: StandaloneWSClient,
    endpoint: String,
    env: String
)(using Executor):

  private val seed = ThreadLocalRandom.nextString(6)

  def start() = apply("lila_start", s"Lila starts: $seed")

  private def apply(key: String, text: String) =
    ws.url(endpoint)
      .post(s"""event,program=lila,env=$env,title=$key text="$text"""")
      .effectFold(
        err => lila.log("influxEvent").error(endpoint, err),
        res => if res.status != 204 then lila.log("influxEvent").error(s"$endpoint ${res.status}")
      )
