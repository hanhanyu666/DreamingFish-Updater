import { reactive } from "vue";

/** How long each home slide stays before the next one. */
export const SLIDE_MS = 7000;

/**
 * Shared by the backdrop, which crossfades the slide images behind the window shade, and the hero
 * copy above it, which owns the timer.
 */
export const carousel = reactive({
  index: 0,
  /** 0–1 through the current slide, for the indicator fill. */
  progress: 0,
});

export function showSlide(index: number, count: number): void {
  carousel.index = count > 0 ? ((index % count) + count) % count : 0;
  carousel.progress = 0;
}
