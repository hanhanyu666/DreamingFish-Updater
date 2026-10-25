import { onBeforeUnmount, onMounted, type Ref } from "vue";

/**
 * What the home rail folds away, one step at a time, when its cards do not fit. The update card
 * always stays whole. The server card first tightens (players and latency move beside its name,
 * the address goes) so the next event can stay; after that the event, the links and the MOTD go.
 */
export const RAIL_FOLDS = ["status", "address", "event", "links", "motd", "server"] as const;

/** The fewest folds at which the rail fits; every fold when even that is not enough. */
export function railFolds(fits: (folds: readonly string[]) => boolean): readonly string[] {
  for (let count = 0; count < RAIL_FOLDS.length; count++) {
    const folds = RAIL_FOLDS.slice(0, count);
    if (fits(folds)) return folds;
  }
  return RAIL_FOLDS;
}

/** Measures the real cards instead of guessing from the window height, so nothing folds early. */
export function useRailFit(rail: Ref<HTMLElement | null>): void {
  let resize: ResizeObserver | null = null;
  let children: MutationObserver | null = null;

  function measure(): void {
    const element = rail.value;
    if (element == null) return;
    // Trying each step forces layout but never paints, so the cards do not flicker.
    const folds = railFolds((candidate) => {
      element.dataset.fold = candidate.join(" ");
      return element.scrollHeight <= element.clientHeight + 1;
    });
    element.dataset.fold = folds.join(" ");
  }

  function watchCards(): void {
    const element = rail.value;
    if (element == null || resize == null) return;
    resize.disconnect();
    resize.observe(element);
    for (const card of Array.from(element.children)) resize.observe(card);
  }

  onMounted(() => {
    if (rail.value == null || typeof ResizeObserver === "undefined") return;
    resize = new ResizeObserver(measure);
    // Cards come and go (an event ends, the server card is switched off); watch the new ones too.
    children = new MutationObserver(() => {
      watchCards();
      measure();
    });
    children.observe(rail.value, { childList: true });
    watchCards();
    measure();
  });

  onBeforeUnmount(() => {
    resize?.disconnect();
    children?.disconnect();
  });
}
