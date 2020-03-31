/*
 * Copyright (c) 2020 Paolo Boni
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.paoloboni.binance

import java.time.Instant

import cats.MonadError
import cats.effect.{Async, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._
import fs2.Stream
import io.circe.Decoder
import io.circe.generic.auto._
import io.github.paoloboni.binance
import io.github.paoloboni.binance.RateLimitInterval._
import io.github.paoloboni.encryption.HMAC
import io.github.paoloboni.http.{HttpClient, QueryStringConverter}
import io.lemonlabs.uri.{QueryString, Url}
import log.effect.LogWriter
import org.http4s.client.Client
import shapeless.tag
import upperbound.{Limiter, Rate}

import scala.concurrent.duration.{MILLISECONDS, _}

sealed class BinanceClient[F[_]: ContextShift: Timer: LogWriter] private (config: BinanceConfig, client: HttpClient[F])(
    implicit F: Async[F]
) extends Decoders {

  /**
    * Returns a stream of Kline objects. It recursively and lazily invokes the endpoint
    * in case the result set doesn't fit in a single page.
    *
    * @param query an `KLines` object containing the query parameters
    * @return the stream of Kline objects
    */
  def getKLines(query: KLines): F[Stream[F, KLine]] = query match {
    case KLines(symbol, binance.Interval(interval), startTime, endTime, limit) =>
      val url = Url(
        scheme = config.scheme,
        host = config.host,
        port = config.port,
        path = "/api/v1/klines",
        query = QueryString.fromPairs(
          "symbol"    -> symbol,
          "interval"  -> interval.toString,
          "startTime" -> startTime.toEpochMilli.toString,
          "endTime"   -> endTime.toEpochMilli.toString,
          "limit"     -> limit.toString
        )
      )
      val result = for {
        klines <- client.get[List[KLine]](url)
      } yield klines
      result.flatMap {
        case loneElement :: Nil => F.pure(Stream(loneElement))
        case init :+ last if (query.endTime.toEpochMilli - last.openTime) > interval.duration.toMillis =>
          F.pure(Stream.fromIterator(init.iterator))
            .map(
              _ ++
                Stream
                  .eval(
                    getKLines(query.copy(startTime = Instant.ofEpochMilli(last.openTime)))
                  )
                  .flatten
            )
        case list =>
          F.pure(Stream.fromIterator(list.iterator))
      }
    case other: KLines =>
      MonadError[F, Throwable].raiseError(
        new RuntimeException(s"${other.interval} is not a valid interval for Binance")
      )
  }

  /**
    * Returns a snapshot of the prices at the time the query is executed.
    *
    * @return A sequence of prices (one for each symbol)
    */
  def getPrices(): F[Seq[Price]] = {
    val url = Url(
      scheme = config.scheme,
      host = config.host,
      port = config.port,
      path = "/api/v3/ticker/price"
    )
    for {
      prices <- client.get[List[Price]](
        url = url,
        weight = 2
      )
    } yield prices
  }

  /**
    * Returns the current balance, at the time the query is executed.
    *
    * @return The balance (free and locked) for each asset
    */
  def getBalance(): F[Map[Asset, Balance]] = {
    def url(currentMillis: Long) = {
      val query       = s"recvWindow=5000&timestamp=${currentMillis.toString}"
      val signature   = HMAC.sha256(config.apiSecret, query)
      val queryString = QueryString.parse(query).addParam("signature", signature)
      Url(
        scheme = config.scheme,
        host = config.host,
        port = config.port,
        path = "/api/v3/account",
        query = queryString
      )
    }
    for {
      currentMillis <- Timer[F].clock.realTime(MILLISECONDS)
      balances <- client.get[BinanceBalances](
        url = url(currentMillis),
        headers = Map("X-MBX-APIKEY" -> config.apiKey),
        weight = 5
      )
    } yield balances.balances.map(b => tag[AssetTag](b.asset) -> Balance(b.free, b.locked)).toMap
  }

  private implicit val orderSideQueryStringConverter: QueryStringConverter[OrderSide] =
    QueryStringConverter.enumEntryConverter(OrderSide)
  private implicit val orderTypeQueryStringConverter: QueryStringConverter[OrderType] =
    QueryStringConverter.enumEntryConverter(OrderType)
  private implicit val timeInForceQueryStringConverter: QueryStringConverter[TimeInForce] =
    QueryStringConverter.enumEntryConverter(TimeInForce)
  private implicit val orderCreateResponseTypeQueryStringConverter: QueryStringConverter[OrderCreateResponseType] =
    QueryStringConverter.enumEntryConverter(OrderCreateResponseType)

  /**
    * Creates an order.
    *
    * @param orderCreate the parameters required to define the order
    *
    * @return The id of the order created
    */
  def createOrder(orderCreate: OrderCreate): F[OrderId] = {

    def urlAndBody(currentMillis: Long) = {
      val requestBody = QueryStringConverter[OrderCreate].to(orderCreate) + s"&recvWindow=5000&timestamp=$currentMillis"
      val signature   = HMAC.sha256(config.apiSecret, requestBody)
      val url = Url(
        scheme = config.scheme,
        host = config.host,
        port = config.port,
        path = "/api/v3/order"
      )
      (url, requestBody + s"&signature=$signature")
    }
    for {
      currentMillis <- Timer[F].clock.realTime(MILLISECONDS)
      (url, requestBody) = urlAndBody(currentMillis)
      orderId <- client
        .post[String, CreateOrderResponse](
          url = url,
          requestBody = requestBody,
          headers = Map("X-MBX-APIKEY" -> config.apiKey)
        )
        .map(response => tag[OrderIdTag][String](response.orderId.toString))
    } yield orderId
  }
}

object BinanceClient {

  def apply[F[_]: ContextShift: Timer: ConcurrentEffect: Client: LogWriter](
      config: BinanceConfig
  )(implicit F: Async[F]): Resource[F, BinanceClient[F]] = {

    val requestRateLimits = for {
      client <- HttpClient[F]
      rateLimits <- client.get[List[RateLimit]](
        Url(
          scheme = config.scheme,
          host = config.host,
          port = config.port,
          path = config.infoUrl
        )
      )(
        Decoder.instance(_.downField("rateLimits").as[List[RateLimit]])
      )
      requestLimits = rateLimits
        .filter(_.rateLimitType == RateLimitType.REQUEST_WEIGHT)
        .map(
          limit =>
            Rate(limit.limit, limit.interval match {
              case SECOND => limit.intervalNum.seconds
              case MINUTE => limit.intervalNum.minutes
              case DAY    => limit.intervalNum.days
            })
        )
    } yield requestLimits

    val requestLimiters = requestRateLimits
      .map(_.map(Limiter.start(_)))
      .map(_.foldLeft(Resource.pure[F, List[Limiter[F]]](List.empty)) { (list, resource) =>
        for {
          l       <- list
          limiter <- resource
        } yield limiter :: l
      })

    Resource
      .suspend(requestLimiters)
      .evalMap { rl =>
        HttpClient
          .rateLimited[F](rl: _*)
          .map(new BinanceClient(config, _))
      }
  }
}
