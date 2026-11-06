/**
 * The answer arrives as one chunk, and the message list remounts when the answer turns out to contain rich markdown.
 * Both events can land in the same tick, so the completed generation is remembered here and the message that renders
 * afterwards picks the reveal up again.
 */
const REVEAL_WINDOW_MS = 5000;

const pendingReveals = new Map<string, number>();

export function markStreamCompletion(generationId?: string | null) {
  if (!generationId) return;
  const now = Date.now();
  pendingReveals.forEach((startedAt, key) => {
    if (now - startedAt > REVEAL_WINDOW_MS) {
      pendingReveals.delete(key);
    }
  });
  pendingReveals.set(generationId, now);
}

export function consumeStreamReveal(generationId?: string | null): boolean {
  if (!generationId) return false;
  const startedAt = pendingReveals.get(generationId);
  if (startedAt === undefined) return false;
  pendingReveals.delete(generationId);
  return Date.now() - startedAt <= REVEAL_WINDOW_MS;
}
