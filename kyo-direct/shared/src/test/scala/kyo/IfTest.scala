package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class IfTest extends AnyFreeSpec with Assertions:

    "unlifted condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if Sync.defer(1).now == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if Sync.defer(1).now == 1 then Sync.defer(2).now else 3
            }
        }
        "impure / pure" in {
            runLiftTest(1) {
                Sync.defer(1).now
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if Sync.defer(1).now == 2 then Sync.defer(2).now else Sync.defer(3).now
            }
        }
    }
    "pure condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if 1 == 1 then Sync.defer(2).now else 3
            }
        }
        "impure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else Sync.defer(3).now
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if 1 == 2 then Sync.defer(2).now else Sync.defer(3).now
            }
        }
    }
end IfTest
