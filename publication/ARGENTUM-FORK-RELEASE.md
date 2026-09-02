# Maintained Argentum public-fetch gate

The MTGallium gitlink is deliberately fixed at
`3eda577fdd10d08e0e62d66b4727ab53f1b41ff5`. Do not substitute upstream
`36c5a0c2...` or another commit to make cloning convenient.

Before an MTGallium public release, the owner must publish an audited maintained
Argentum fork (or other anonymously fetchable source) containing this exact
object, then update the public candidate's `.gitmodules` URL to that existing
location. Audit and retain privately:

- maintained-fork remote and public commit identity;
- fork ancestry and any maintained patches;
- authorship and third-party-license/notices inventory;
- reproducible anonymous `git submodule update --init --recursive` evidence.

The current configured upstream URL was tested during preparation and did not
serve this object. The exporter preserves the gitlink and current `.gitmodules`
only as a fail-closed placeholder; it does not claim public clone success.
