# Information, Belief, and Search in MTGallium

[`information-and-search.tex`](information-and-search.tex) is the project's
theoretical reference, version 2.0. It defines what a player knows, how beliefs
weigh the possibilities a player cannot rule out, what a value estimate means,
and what MTGallium's search computes. A single example runs throughout: whether
to cast into mana that may be holding up Counterspell. The paper's terms are
listed in [terminology](../terminology.md), and its last sections map each one
to the source.

The delivered PDF is
[`output/pdf/information-and-search.pdf`](../../output/pdf/information-and-search.pdf).
To rebuild it, use TeX Live with LuaLaTeX and the TeX Gyre fonts:

```bash
latexmk -lualatex -interaction=nonstopmode -halt-on-error \
  -outdir=build/whitepaper docs/whitepapers/information-and-search.tex
cp build/whitepaper/information-and-search.pdf output/pdf/
```
