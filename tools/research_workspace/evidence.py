"""Bounded retrieval of private retained evidence, without interpreting outcomes.

The source-owned verifier remains the manifest authority. COMPLETE and successful
byte verification do not establish scientific validity. JSON pointers retrieve
existing fields; the source-native FEATURES suite is a separate semantic feature
generation operation. Canonical/replay contents are never included in summaries.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import stat
from typing import Callable


MANIFEST_NAME = "research-run-manifest.json"
DISCOVERY_NAMES = (MANIFEST_NAME, "experiment.json", "request.json")
MAX_DISCOVERY_BYTES = 1024 * 1024
MAX_JSON_BYTES = 64 * 1024 * 1024
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_LIMITATIONS = [
    "Verification authenticates retained bytes, not scientific validity or a research outcome.",
    "Manifest COMPLETE means artifact retention completed; stopped, refused, excluded, and missing data retain their original meaning.",
    "JSON field retrieval does not generate semantic features; use the source-native FEATURES suite for that operation.",
]
_DECLARED_FIELDS = (
    "schemaVersion", "researchRunIdentity", "runIdentity", "experimentIdentity",
    "protocol", "state", "sourceProvenance", "sourceRevision", "mtgalliumRevision",
    "argentumRevision", "outerCommit", "expectedArgentumRevision",
    "expectedEngineCommit", "checkedOutEngineCommit",
)
_DESIGN_FIELDS = ("name", "kind", "design", "lineage", "source", "build")


class EvidenceError(ValueError):
    """A retrieval refusal; never a game result or scientific disposition."""


class _JsonNumber(str):
    """A validated JSON number token, preserved exactly during field retrieval."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def _absolute(path: Path) -> Path:
    path = Path(path)
    _require(".." not in path.parts, "Paths must not contain parent traversal")
    return path.absolute()


def _open_directory(path: Path, *, create: bool = False) -> int:
    """Open every path component without following links, including ancestors."""
    path = _absolute(path)
    descriptor = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in path.parts[1:]:
            if create:
                try:
                    os.mkdir(component, mode=0o700, dir_fd=descriptor)
                except FileExistsError:
                    pass
            following = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                dir_fd=descriptor)
            os.close(descriptor)
            descriptor = following
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _directory(path: Path) -> Path:
    path = _absolute(path)
    try:
        descriptor = _open_directory(path)
        os.close(descriptor)
    except OSError as error:
        raise EvidenceError(f"Cannot open evidence directory without symlinks: {path}: {error}") from error
    return path


def _relative_parts(relative: str) -> list[str]:
    _require(isinstance(relative, str) and bool(relative), "Artifact path must be a nonempty relative string")
    _require("\\" not in relative and not PureWindowsPath(relative).drive,
             "Artifact paths must use relative forward-slash components")
    parts = relative.split("/")
    _require(all(part not in ("", ".", "..") for part in parts),
             "Artifact path contains absolute, empty, or traversal components")
    _require("\x00" not in relative, "Artifact path contains a null byte")
    return parts


def _read_bounded(directory: Path, relative: str, maximum: int) -> bytes:
    parts = _relative_parts(relative)
    descriptor = None
    file_descriptor = None
    try:
        descriptor = _open_directory(directory)
        for part in parts[:-1]:
            following = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                dir_fd=descriptor)
            os.close(descriptor)
            descriptor = following
        file_descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                  dir_fd=descriptor)
        info = os.fstat(file_descriptor)
        _require(stat.S_ISREG(info.st_mode), f"Artifact must be a regular file: {relative}")
        _require(info.st_size <= maximum,
                 f"JSON input exceeds the {maximum}-byte limit: {relative}; use a specialized streaming tool")
        with os.fdopen(file_descriptor, "rb") as handle:
            file_descriptor = None
            data = handle.read(maximum + 1)
        _require(len(data) <= maximum,
                 f"JSON input exceeds the {maximum}-byte limit: {relative}; use a specialized streaming tool")
        return data
    except OSError as error:
        raise EvidenceError(f"Cannot read artifact without symlinks: {relative}: {error}") from error
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if descriptor is not None:
            os.close(descriptor)


def _object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON object key")
        result[key] = value
    return result


def _constant(value: str) -> None:
    raise EvidenceError(f"Non-finite JSON number is unsupported: {value}")


def _finite_float(value: str) -> float:
    result = float(value)
    _require(math.isfinite(result), "JSON metadata number exceeds finite floating-point representation")
    return result


def _load_json(data: bytes, *, preserve_numbers: bool = False) -> object:
    try:
        return json.loads(data, object_pairs_hook=_object, parse_constant=_constant,
                          parse_int=_JsonNumber if preserve_numbers else int,
                          parse_float=_JsonNumber if preserve_numbers else _finite_float)
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError(f"Cannot parse JSON: {error}") from error


def _json_bytes(value: object) -> bytes:
    # The standard encoder rounds decimal tokens through binary floats. Retrieval
    # keeps original number tokens, as well as every null, array entry, and error.
    def encode(item: object) -> str:
        if isinstance(item, _JsonNumber):
            return str(item)
        if isinstance(item, dict):
            _require(all(isinstance(key, str) for key in item), "JSON object keys must be strings")
            return "{" + ",".join(json.dumps(key, ensure_ascii=True) + ":" + encode(child)
                                  for key, child in item.items()) + "}"
        if isinstance(item, list):
            return "[" + ",".join(encode(child) for child in item) + "]"
        return json.dumps(item, ensure_ascii=True, allow_nan=False)

    try:
        return (encode(value) + "\n").encode("utf-8")
    except (ValueError, TypeError, RecursionError) as error:
        raise EvidenceError(f"Cannot serialize retrieved JSON: {error}") from error


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _declared(document: object, *, discovery: bool = False) -> dict:
    if not isinstance(document, dict):
        return {}
    fields = _DECLARED_FIELDS + _DESIGN_FIELDS if discovery else _DECLARED_FIELDS
    declaration = {key: document[key] for key in fields if key in document}
    if isinstance(document.get("bindings"), dict) and "protocol" in document["bindings"]:
        declaration["bindings"] = {"protocol": document["bindings"]["protocol"]}
    return declaration


def find_runs(root: Path, query: str = "", limit: int = 50) -> dict:
    """Discover declarations only, deterministically and without a persistent index.

    At most ``limit`` entries and errors are returned. A candidate may have multiple
    marker documents; parse/read failures are reported separately and no reports
    or outcomes are opened. Declarations and marker hashes remain unverified.
    """
    _require(type(limit) is int and 1 <= limit <= 1000, "Discovery limit must be between 1 and 1000")
    _require(isinstance(query, str), "Discovery query must be text")
    root = _directory(root)
    result = {"root": str(root), "query": query, "limit": limit, "entries": [],
              "errors": [], "truncated": False, "errorsTruncated": False}

    def record_error(path: object, message: str) -> None:
        if len(result["errors"]) < limit:
            result["errors"].append({"path": str(path), "error": message})
        else:
            result["errorsTruncated"] = True

    def walk_error(error: OSError) -> None:
        record_error(error.filename or root, str(error))

    for current, directories, files in os.walk(root, followlinks=False, onerror=walk_error):
        directories.sort()
        names = [name for name in DISCOVERY_NAMES if name in files]
        if not names:
            continue
        directory = Path(current)
        documents = []
        for name in names:
            try:
                data = _read_bounded(directory, name, MAX_DISCOVERY_BYTES)
                document = _load_json(data)
                _require(isinstance(document, dict), "Discovery marker must be a JSON object")
                documents.append({"name": name, "sha256": _sha256(data),
                                  "declared": _declared(document, discovery=True)})
            except EvidenceError as error:
                record_error(directory / name, str(error))
        entry = {"directory": str(directory), "status": "unverified", "documents": documents}
        # Only path and observed declarations are searchable. Missing metadata is
        # unknown; a report is never opened to fill a missing source attribution.
        if query.casefold() not in json.dumps(entry, ensure_ascii=True).casefold():
            continue
        if len(result["entries"]) == limit:
            result["truncated"] = True
            break
        result["entries"].append(entry)
    return result


def _verified(directory: Path, verifier: Callable[[Path], dict]) -> tuple[Path, dict, str]:
    directory = _directory(directory)
    verified = verifier(directory)
    _require(isinstance(verified, dict), "Source verifier returned no verification object")
    manifest = verified.get("manifest")
    manifest_hash = verified.get("manifestSha256")
    _require(isinstance(manifest, dict), "Source verifier did not return its manifest")
    _require(isinstance(manifest_hash, str) and bool(_SHA256.fullmatch(manifest_hash)),
             "Source verifier did not return a manifest SHA-256")
    data = _read_bounded(directory, MANIFEST_NAME, MAX_JSON_BYTES)
    _require(_sha256(data) == manifest_hash and _load_json(data) == manifest,
             "Manifest changed or disagrees with the source verifier result")
    # The source schema defaults an omitted state to COMPLETE. The raw manifest
    # remains unchanged, so inspection does not invent an observed state field.
    _require(manifest.get("state", "COMPLETE") == "COMPLETE", "Source verifier did not return a COMPLETE manifest")
    _require(isinstance(manifest.get("researchRunIdentity"), str) and bool(manifest["researchRunIdentity"]),
             "Verified manifest has no research-run identity")
    artifacts = manifest.get("artifacts")
    _require(isinstance(artifacts, list) and bool(artifacts), "Verified manifest has no artifact inventory")
    paths = set()
    for item in artifacts:
        _require(isinstance(item, dict), "Invalid verified artifact entry")
        relative = item.get("relativePath")
        _relative_parts(relative)
        _require(relative != MANIFEST_NAME and relative not in paths, "Duplicate or self-registering manifest artifact")
        paths.add(relative)
        _require(isinstance(item.get("sha256"), str) and bool(_SHA256.fullmatch(item["sha256"])),
                 "Verified artifact lacks a SHA-256")
        _require(type(item.get("bytes")) is int and item["bytes"] >= 0, "Verified artifact lacks a byte count")
    return directory, manifest, manifest_hash


def inspect_run(directory: Path, verifier: Callable[[Path], dict]) -> dict:
    """Verify through source code and return inventory without artifact contents."""
    directory, manifest, manifest_hash = _verified(directory, verifier)
    return {"directory": str(directory), "status": "bytes-verified",
            "manifestSha256": manifest_hash, "declared": _declared(manifest),
            "artifacts": [{key: item[key] for key in ("relativePath", "sha256", "bytes")}
                          for item in manifest["artifacts"]], "limitations": list(_LIMITATIONS)}


def _selected(directory: Path, artifact: str, verifier: Callable[[Path], dict]) -> tuple[Path, dict, str, dict, object]:
    directory, manifest, manifest_hash = _verified(directory, verifier)
    _relative_parts(artifact)
    matches = [item for item in manifest["artifacts"] if item["relativePath"] == artifact]
    _require(len(matches) == 1, "Selected artifact is not registered exactly once in the verified manifest")
    item = matches[0]
    _require(item["bytes"] <= MAX_JSON_BYTES,
             "Selected JSON exceeds 64 MiB; use a specialized streaming tool")
    data = _read_bounded(directory, artifact, MAX_JSON_BYTES)
    _require(len(data) == item["bytes"] and _sha256(data) == item["sha256"],
             "Selected artifact bytes changed or disagree with the verified manifest")
    return directory, manifest, manifest_hash, item, _load_json(data, preserve_numbers=True)


def _json_type(value: object) -> str:
    if value is None:
        return "null"
    if isinstance(value, _JsonNumber):
        return "number"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, dict):
        return "object"
    if isinstance(value, list):
        return "array"
    return "string"


def describe_artifact(directory: Path, artifact: str, verifier: Callable[[Path], dict]) -> dict:
    """Explicit JSON shape preview: top-level keys and type, never field values."""
    directory, manifest, manifest_hash, item, value = _selected(directory, artifact, verifier)
    result = {"directory": str(directory), "researchRunIdentity": manifest["researchRunIdentity"],
              "manifestSha256": manifest_hash, "artifact": dict(item),
              "jsonType": _json_type(value), "limitations": list(_LIMITATIONS)}
    if isinstance(value, dict):
        result["topLevelKeys"] = list(value)
    return result


def _pointer(value: object, pointer: str) -> object:
    _require(isinstance(pointer, str), "JSON pointer must be text")
    if pointer == "":
        return value
    _require(pointer.startswith("/"), "RFC 6901 JSON pointer must be empty or begin with '/' (URI fragments are unsupported)")
    for encoded in pointer[1:].split("/"):
        _require(re.search(r"~(?:[^01]|$)", encoded) is None, "Invalid RFC 6901 escape in JSON pointer")
        token = encoded.replace("~1", "/").replace("~0", "~")
        if isinstance(value, dict):
            _require(token in value, "JSON pointer does not identify an existing object field")
            value = value[token]
        elif isinstance(value, list):
            _require(bool(re.fullmatch(r"0|[1-9][0-9]*", token)), "JSON pointer array index is not canonical")
            # Compare text first so an arbitrarily long index cannot trigger the
            # interpreter's integer-conversion limit or consume large resources.
            maximum = str(len(value) - 1)
            _require(bool(value) and (len(token), token) <= (len(maximum), maximum),
                     "JSON pointer array index is out of bounds")
            value = value[int(token)]
        else:
            raise EvidenceError("JSON pointer traverses a scalar value")
    return value


def _write_export(destination: Path, value_data: bytes, receipt_data: bytes) -> None:
    destination = _absolute(destination)
    _require(bool(destination.name), "Export destination must be a fresh child directory")
    parent_descriptor = None
    output_descriptor = None
    try:
        parent_descriptor = _open_directory(destination.parent, create=True)
        # mkdir is the freshness check; existing files, empty directories and
        # dangling symlinks all refuse without overwriting anything.
        os.mkdir(destination.name, mode=0o700, dir_fd=parent_descriptor)
        output_descriptor = os.open(destination.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                    dir_fd=parent_descriptor)
        for name, data in (("value.json", value_data), ("receipt.json", receipt_data)):
            descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 mode=0o600, dir_fd=output_descriptor)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
    except OSError as error:
        raise EvidenceError(f"Export requires a fresh destination without symlinks: {destination}: {error}") from error
    finally:
        if output_descriptor is not None:
            os.close(output_descriptor)
        if parent_descriptor is not None:
            os.close(parent_descriptor)


def extract_json(directory: Path, artifact: str, pointer: str, destination: Path,
                 verifier: Callable[[Path], dict], extractor: dict) -> dict:
    """Export a whole selected JSON value and a separate derivative receipt.

    The caller must validate that ``destination`` is private and outside source
    checkouts. This function additionally requires freshness and refuses all
    symlink/traversal paths. No rows are filtered and no outcomes are inferred.
    """
    _require(isinstance(extractor, dict), "Extractor source binding is required")
    _require(isinstance(extractor.get("sourceRevision"), str) and bool(extractor["sourceRevision"].strip()),
             "Extractor sourceRevision is required")
    _require(type(extractor.get("dirty")) is bool, "Extractor dirty state is required")
    _require(isinstance(extractor.get("scriptSha256"), str) and bool(_SHA256.fullmatch(extractor["scriptSha256"])),
             "Extractor scriptSha256 is required")
    directory, manifest, manifest_hash, item, value = _selected(directory, artifact, verifier)
    selected = _pointer(value, pointer)
    value_data = _json_bytes(selected)
    destination = _absolute(destination)
    receipt = {
        "schemaVersion": 1,
        "operation": "json-pointer-retrieval",
        "input": {
            "directory": str(directory), "researchRunIdentity": manifest["researchRunIdentity"],
            "manifestSha256": manifest_hash, "artifact": dict(item),
            "declaredProducer": {"researchRunIdentity": manifest["researchRunIdentity"],
                                 "artifactDeclarations": _declared(value)},
        },
        "pointer": pointer,
        "extractor": dict(extractor),
        "output": {"directory": str(destination), "relativePath": "value.json",
                   "sha256": _sha256(value_data), "bytes": len(value_data)},
        "limitations": list(_LIMITATIONS),
    }
    receipt_data = _json_bytes(receipt)
    # Number-token wrappers are internal to lossless retrieval. Return ordinary
    # JSON metadata to CLI callers, matching the persisted receipt's field types.
    receipt = _load_json(receipt_data)
    _write_export(destination, value_data, receipt_data)
    return receipt
