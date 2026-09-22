import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Exercise recovery without a browser: storage mutations fail the test immediately.
const html = fs.readFileSync(new URL('../research_workspace/replay_notes_backup.html', import.meta.url), 'utf8');
const script = html.split('<script>')[1].split('</script>')[0].replace('__SELECTED_DIGESTS__', '["safe-digest"]');
const note = {source: {safeBundleSha256: 'safe-digest', frameIndex: 3}, thoughts: 'Preserve 原文'};
const entries = [
  ['mtgallium.replay-review-situation-note.v1.safe-digest.3', JSON.stringify(note)],
  ['mtgallium.replay-review-situation-note.v1.other.4', '{unparseable'],
  ['unrelated.setting', 'private unrelated value'],
];
const nodes = Object.fromEntries(['status', 'details', 'download', 'transfer'].map(id => [id, {disabled: true, textContent: ''}]));
let backup;
let posts = 0;
let clicked = false;
const context = {
  document: {getElementById: id => nodes[id], createElement: () => ({click() {clicked = true;}})},
  localStorage: {
    length: entries.length, key: index => entries[index][0],
    getItem: key => entries.find(entry => entry[0] === key)[1],
    setItem() {throw new Error('Storage mutation');}, removeItem() {throw new Error('Storage deletion');},
    clear() {throw new Error('Storage cleared');},
  },
  Blob: class {constructor(parts) {backup = JSON.parse(parts[0]);}},
  URL: {createObjectURL: () => 'blob:synthetic', revokeObjectURL() {}},
  setTimeout() {},
  fetch: async (path, request) => {posts++; assert.equal(path, '/api/replay-review-notes');
    assert.deepEqual(JSON.parse(request.body), note); return {ok: true, json: async () => ({note})};},
};
vm.runInNewContext(script, context);
assert.equal(nodes.transfer.disabled, true);
assert.match(nodes.status.textContent, /Found 1 nonempty notes/);
nodes.download.onclick();
assert.equal(clicked, true);
assert.equal(backup.entries.length, 2);
assert.equal(backup.entries[1].raw, '{unparseable');
assert.equal(nodes.transfer.disabled, false);
await nodes.transfer.onclick();
assert.equal(posts, 1);
assert.match(nodes.status.textContent, /All 1 notes copied/);
assert.equal(nodes.transfer.disabled, true);
