// Read outside the game only for what it registers; the bodies are never run here.
const twice = (n) => n * 2;

step('double', (t, n) => twice(n));

test('adds', { scene: 'script' }, (t) => {
    t.expect(1 + 1).equals(2);
});

test('fails', { scene: 'script' }, (t) => {
    t.expect(1 + 1).equals(3);
});
