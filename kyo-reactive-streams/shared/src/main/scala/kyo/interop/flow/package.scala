package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import scala.annotation.nowarn

package object flow:
    def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int,
        emitStrategy: EmitStrategy = EmitStrategy.Eager
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Stream[T, Async] < (Scope & Sync) =
        for
            subscriber <- StreamSubscriber[T](bufferSize, emitStrategy)
            _          <- Sync.defer(publisher.subscribe(subscriber))
            stream     <- subscriber.stream
        yield stream

    @nowarn("msg=anonymous")
    def subscribeToStream[T, S](
        using Isolate[S, Sync, Any]
    )(
        stream: Stream[T, S & Sync],
        subscriber: Subscriber[? >: T]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Subscription < (Scope & Sync & S) =
        StreamSubscription.subscribe(stream, subscriber)

    def streamToPublisher[T, S](
        using Isolate[S, Sync, Any]
    )(
        stream: Stream[T, S & Sync]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Publisher[T] < (Scope & Sync & S) = StreamPublisher[T, S](stream)

end flow
