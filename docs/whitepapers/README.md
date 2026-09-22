# Information, Belief, and Search

[`information-and-search.tex`](information-and-search.tex) is the LaTeX source
for the project white paper, version 1.4. Four parts follow a signal game from
observations and compatible histories through beliefs, values, simulation,
search, and implementation. Worked examples include a decision-to-decision
belief update, evolving search statistics, failed execution, a root-information
cutoff at an opponent decision, and sufficient compression. The appendices
provide notation, classified contracts, 16 exercises, and separate solutions.

The paper is a theoretical reference, not a completeness claim about the
current implementation or a research result. Its source-navigation
section links the public `main` module layout. The glossary connects
`InformationStateRepresentation` to the theoretical player-history model
developed in this paper.

Build from the repository root with a recent TeX Live installation containing
LuaLaTeX, the LaTeX tagging packages, TeX Gyre fonts, and the standard recommended
and extra packages. The delivered build was checked with TeX Live 2026:

```sh
mkdir -p build/whitepaper output/pdf
latexmk -lualatex -interaction=nonstopmode -halt-on-error \
  -outdir=build/whitepaper docs/whitepapers/information-and-search.tex
cp build/whitepaper/information-and-search.pdf output/pdf/information-and-search.pdf
```

The build uses no shell escape and no bibliography processor. Diagrams are
native TikZ; the three bibliography entries are included in the source.
Allow `latexmk` to complete its repeat passes so references and generated
MathML associations settle. The delivered PDF is
`output/pdf/information-and-search.pdf`.

The PDF includes English-language metadata, document structure tags, table
header cells, diagram alternative descriptions, bookmarks, and associated
MathML for its tagged formulas. This follows the
[LaTeX tagging project's build guidance](https://tagging-project.latex-project.org/documentation/usage-instructions).
The final artifact was checked for these structures and visually inspected.
Full screen-reader testing, mathematical speech review, and PDF/UA conformance
validation have not been performed; the edition does not claim that certification.

Version 1.4 completes the limited copyedit: evaluator and node introductions
explain their purpose, execution and record-update prose describes the required
agreement before its equation, and the application-scope paragraph is shorter.
The visitation example is identified as a variant, exercise headings are more
neutral, and the role-exchange detail appears with symmetry. Structure, formal
results, calculations, orange/blue coding, and the source revision are preserved.
