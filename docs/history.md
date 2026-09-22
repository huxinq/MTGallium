# Historical research source

Read an older result with its recorded MTGallium and Argentum revisions,
configuration and inputs. Retrieve the producing checkout through that result's
source records when its commands or formats have since been retired.

The pre-publication history is retained locally, separately from the squashed
public release. Its recovery points include `f839521e2d88b583b0b292232ab1df2e7f6970cf`
for the study-specific workbench and `f284b95e5c40b17ac193edfe5bbe7712a6f46e40`
for earlier tournament and neural diagnostic commands. These revisions are not
included in a fresh clone of the squashed release.

In a checkout containing the retained history, for example:

```bash
git show f839521:docs/research-workflow.md
git show f839521:docs/protocols/factual-residual.md
```

Current experiments use the [research library and CLI](research-workbench.md).
The [Argentum integration record](history/argentum-performance-integration.md)
describes the upstream contributions included in the pinned engine.
