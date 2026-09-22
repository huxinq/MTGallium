# Argentum policy integration

Configure `game.ai.search-teacher.known-decks` with exactly the replay seats `p0` and `p1`.
Each declaration is an open-deck card-count map and must match that seat's actual game deck;
the controller rejects a replay whose initial deck multiset differs. Declarations may differ
between seats and have any positive size supported by the game setup.

```yaml
game:
  ai:
    search-teacher:
      known-decks:
        p0: { "[Mountain]": 24, "[Lightning Bolt]": 4 }
        p1: { "[Island]": 24, "[Counterspell]": 4 }
```
