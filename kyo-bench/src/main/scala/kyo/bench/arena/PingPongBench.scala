package kyo.bench.arena

class PingPongBench extends ArenaBench.ForkOnly(()):

    val depth = 1000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*

        def repeat[A](n: Int)(io: IO[A]): IO[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
            for
                ref   <- IO.ref(n)
                queue <- Queue.bounded[IO, Unit](1)
                effect = queue.offer(()).start >>
                    queue.take >>
                    ref
                        .modify(n =>
                            (n - 1, if n == 1 then deferred.complete(()) else IO.unit)
                        )
                        .flatten
                _ <- repeat(depth)(effect.start)
            yield ()

        for
            deferred <- IO.deferred[Unit]
            _        <- iterate(deferred, depth).start
            _        <- deferred.get
        yield ()
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def repeat[A](n: Int)(io: A < (Async & Abort[Closed])): A < (Async & Abort[Closed]) =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def iterate(promise: Promise[Unit, Any], n: Int): Unit < (Async & Abort[Closed]) =
            for
                ref  <- AtomicInt.init(n)
                chan <- Channel.initUnscoped[Unit](1)
                effect =
                    for
                        _ <- Fiber.initUnscoped(chan.put(()))
                        _ <- chan.take
                        n <- ref.decrementAndGet
                        _ <- if n == 0 then promise.completeUnit else Kyo.unit
                    yield ()
                _ <- repeat(depth)(Fiber.initUnscoped(effect))
            yield ()

        for
            promise <- Promise.init[Unit, Any]
            _       <- Fiber.initUnscoped(iterate(promise, depth))
            _       <- promise.get
        yield ()
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            if n <= 1 then zio
            else zio *> repeat(n - 1)(zio)

        def iterate(promise: Promise[Nothing, Unit], n: Int): ZIO[Any, Nothing, Any] =
            for
                ref   <- Ref.make(n)
                queue <- Queue.bounded[Unit](1)
                effect = queue.offer(()).forkDaemon *>
                    queue.take *>
                    ref
                        .modify(n =>
                            (if n == 1 then promise.succeed(()) else ZIO.unit, n - 1)
                        )
                        .flatten
                _ <- repeat(depth)(effect.forkDaemon)
            yield ()

        for
            promise <- Promise.make[Nothing, Unit]
            _       <- iterate(promise, depth).forkDaemon
            _       <- promise.await
        yield ()
        end for
    end zioBench
end PingPongBench
