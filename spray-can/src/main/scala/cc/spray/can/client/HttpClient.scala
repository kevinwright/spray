/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can
package client

import cc.spray.io._
import akka.util.Duration
import com.typesafe.config.{Config, ConfigFactory}
import java.util.concurrent.TimeUnit
import akka.event.LoggingAdapter
import pipelines.{SslTlsSupport, ClientSSLEngineProvider, TickGenerator, ConnectionTimeouts}

/**
 * Reacts to [[cc.spray.can.HttpClient.Connect]] messages by establishing a connection to the remote host.
 * If the connection has been established successfully a new actor is spun up for the connection, which replies to the
 * sender of the [[cc.spray.can.HttpClient.Connect]] message with a [[cc.spray.can.HttpClient.Connected]] message.
 *
 * You can then send [[cc.spray.can.model.HttpRequestPart]] instances to the connection actor, which are going to be
 * replied to with [[cc.spray.can.model.HttpResponsePart]] messages (or [[akka.actor.Status.Failure]] instances
 * in case of errors).
 */
class HttpClient(ioWorker: IoWorker,
                 config: Config = ConfigFactory.load)
                (implicit sslEngineProvider: ClientSSLEngineProvider)
  extends IoClient(ioWorker) with ConnectionActors {

  protected lazy val pipeline: PipelineStage =
    HttpClient.pipeline(new ClientSettings(config), log)
}

object HttpClient {

  private[can] def pipeline(settings: ClientSettings,
                            log: LoggingAdapter)
                           (implicit sslEngineProvider: ClientSSLEngineProvider): PipelineStage = {
    ClientFrontend(settings.RequestTimeout, log) ~>
    PipelineStage.optional(settings.ResponseChunkAggregationLimit > 0,
      ResponseChunkAggregation(settings.ResponseChunkAggregationLimit.toInt)) ~>
    ResponseParsing(settings.ParserSettings, log) ~>
    RequestRendering(settings) ~>
    PipelineStage.optional(settings.IdleTimeout > 0, ConnectionTimeouts(settings.IdleTimeout, log)) ~>
    PipelineStage.optional(settings.SSLEncryption, SslTlsSupport(sslEngineProvider, log)) ~>
    PipelineStage.optional(
      settings.ReapingCycle > 0 && settings.IdleTimeout > 0,
      TickGenerator(Duration(settings.ReapingCycle, TimeUnit.MILLISECONDS))
    )
  }

  ////////////// COMMANDS //////////////
  // HttpRequestParts +
  type Connect = IoClient.Connect;                           val Connect = IoClient.Connect
  type Close = IoClient.Close;                               val Close = IoClient.Close
  type Send = IoClient.Send;                                 val Send = IoClient.Send
  type Tell = IoClient.Tell;                                 val Tell = IoClient.Tell
  type SetRequestTimeout = ClientFrontend.SetRequestTimeout; val SetRequestTimeout = ClientFrontend.SetRequestTimeout

  ////////////// EVENTS //////////////
  // HttpResponseParts +
  val Connected = IoClient.Connected
  type Closed = IoClient.Closed;     val Closed = IoClient.Closed
  type AckSend = IoClient.AckSend;   val AckSend = IoClient.AckSend
  type Received = IoClient.Received; val Received = IoClient.Received

}

