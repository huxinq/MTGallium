"""Single Python catalog of supported workbench capabilities."""
from dataclasses import dataclass
from types import MappingProxyType
from typing import Literal, Mapping


Gate = Literal['rehearsal', 'authenticated-inputs', 'embedded-pilot']
DESIGN_FIELDS = ('question', 'learner', 'target', 'control', 'sharedComponents', 'intervention',
                 'population', 'primaryMeasure', 'decisionRule', 'allowedClaims', 'limitations', 'dataUse')
POPULATION_DESIGN_FIELDS = ('question', 'population', 'allowedClaims', 'limitations', 'dataUse')


@dataclass(frozen=True, kw_only=True)
class Capability:
    """Workbench routing and preparation requirements; native plans own science."""

    gate: Gate
    purpose: str
    suite: str
    design_fields: tuple[str, ...] = DESIGN_FIELDS
    requires_deck: bool = True
    uses_execution_threads: bool = False
    requires_smoke_seed: bool = False


CAPABILITIES: Mapping[str, Capability] = MappingProxyType({
    'calibration': Capability(
        gate='rehearsal', purpose='Fixed paired gameplay',
        suite='search-teacher-calibration',
        uses_execution_threads=True, requires_smoke_seed=True),
    'sequential': Capability(
        gate='rehearsal', purpose='Sequential paired gameplay',
        suite='search-teacher-sequential',
        uses_execution_threads=True, requires_smoke_seed=True),
    'position-screen': Capability(
        gate='rehearsal', purpose='Matched saved-position search',
        suite='position-bank-screen',
        uses_execution_threads=True),
    'position-features': Capability(
        gate='authenticated-inputs', purpose='Reconstruct acting-player features from a position bank',
        suite='position-bank-screen',
        design_fields=POPULATION_DESIGN_FIELDS, uses_execution_threads=True),
    'position-bank': Capability(
        gate='authenticated-inputs', purpose='Derive a bank from retained games',
        suite='real-game-position-bank',
        design_fields=POPULATION_DESIGN_FIELDS),
    'terminal-kernel-study': Capability(
        gate='embedded-pilot', purpose='Development targets, frozen fit and held-out comparison',
        suite='terminal-kernel-study'),
    'terminal-target-sensitivity': Capability(
        gate='embedded-pilot', purpose='Measure changed conditional targets with frozen models',
        suite='terminal-target-sensitivity'),
    'terminal-prediction-diagnostic': Capability(
        gate='authenticated-inputs', purpose='Diagnose frozen development predictions',
        suite='terminal-prediction-diagnostic',
        requires_deck=False),
    'factual-residual-study': Capability(
        gate='authenticated-inputs', purpose='Fit and diagnose a V2 residual from admitted factual trajectories',
        suite='factual-residual-study'),
    'direct-attack-kernel-screen': Capability(
        gate='authenticated-inputs', purpose='Compare direct decisions on declared development roots',
        suite='direct-attack-kernel-screen'),
    'research-transfer-audit': Capability(
        gate='authenticated-inputs', purpose='Join saved-position and deployment evidence',
        suite='research-transfer-audit',
        requires_deck=False),
    'campaign-data-snapshot': Capability(
        gate='authenticated-inputs', purpose='Inspect recorded campaign population use',
        suite='campaign-data-snapshot',
        design_fields=POPULATION_DESIGN_FIELDS, requires_deck=False),
})


def catalog():
    return {'capabilities': [dict(kind=kind, gate=capability.gate, purpose=capability.purpose, suite=capability.suite,
                                  plan=True, launch=True, verify='artifact-bytes',
                                  inspection='registered-artifact-inventory')
                             for kind, capability in CAPABILITIES.items()],
            'nativeRoutes': [
                dict(suite='campaign-data-use', purpose='Append explicit prospective or retrospective population use',
                     route='--suite campaign-data-use --profile USE.json --output REGISTRY',
                     reason='An append-only campaign registry has different output semantics from a fresh attempt.'),
                dict(suite='gameplay-summary', purpose='Authenticate complete-game populations and report lengths',
                     route='--suite gameplay-summary --run-directory RUN'),
                dict(suite='search-profile-summary', purpose='Read retained registered JFR cost evidence',
                     route='--suite search-profile-summary --profile REGISTERED.jfr'),
                dict(suite='search-teacher-continuation', purpose='Explicit parent-linked continuation protocol',
                     route='See docs/research-workflow.md#optional-continuation-of-stopped-gameplay',
                     reason='Forking a draft does not authorize or implement statistical continuation.')],
            'limits': ['A gate checks the capability named in this catalog, not research validity.',
                       'Field export preserves recorded JSON; position-features generates the existing policy representation.']}
