package kyo.bench.arena

class SuspensionBench extends ArenaBench.SyncAndFork(()):

    def catsBench() =
        import cats.effect.*

        IO(())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
    end catsBench

    def kyoBench() =
        import kyo.*

        Sync.defer(())
            .flatMap(_ => Sync.defer(())).map(_ => ()).flatMap(_ => Sync.defer(())).map(_ => ())
            .flatMap(_ => Sync.defer(())).map(_ => ()).flatMap(_ => Sync.defer(())).map(_ => ())
            .flatMap(_ => Sync.defer(())).map(_ => ()).flatMap(_ => Sync.defer(())).map(_ => ())
            .flatMap(_ => Sync.defer(())).map(_ => ()).flatMap(_ => Sync.defer(())).map(_ => ())
            .flatMap(_ => Sync.defer(())).map(_ => ()).flatMap(_ => Sync.defer(())).map(_ => ())
    end kyoBench

    def zioBench() =
        import zio.*
        ZIO.succeed(())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
    end zioBench
end SuspensionBench
