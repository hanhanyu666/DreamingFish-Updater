/** One filled rectangle of an 8×8 face, in face pixels. */
export interface FacePixel {
  key: string;
  x: number;
  y: number;
  w: number;
  h: number;
  fill: string;
}

const HAIR = ["#1E3A5F", "#C9C2B8", "#6B2E1F", "#2F4A2A", "#3B2A1F", "#C9A24A", "#7A4A30", "#15171C", "#8E5A3A"];
const SKIN = ["#E2AE84", "#D9A47C", "#F0C29E", "#C78E62", "#D39A6A", "#B07A52"];
const EYES = ["#1E6FB8", "#7A3FA8", "#2F7A55", "#3A5FB0", "#5A3A22", "#2A8C8C"];

function shade(hex: string, amount: number): string {
  const value = Number.parseInt(hex.slice(1), 16);
  return "#" + [16, 8, 0]
    .map((shift) => Math.round(((value >> shift) & 255) * (1 - amount)).toString(16).padStart(2, "0"))
    .join("");
}

/**
 * A Minecraft-style 8×8 head for a player name, as in the design: hair, skin, eyes, nose and mouth.
 * The same name always draws the same face; the title bar and team cards share it.
 */
export function pixelFace(name: string): FacePixel[] {
  let hash = 2166136261;
  for (const char of name.trim() || "?") hash = Math.imul(hash ^ char.codePointAt(0)!, 16777619) >>> 0;
  const pick = <T>(list: readonly T[], shift: number): T => list[(hash >>> shift) % list.length];
  const hair = pick(HAIR, 0);
  const skin = pick(SKIN, 5);
  const eye = pick(EYES, 10);
  const fringe = (hash >>> 15) & 1;
  const pixels: Omit<FacePixel, "key">[] = [
    { x: 0, y: 0, w: 8, h: 8, fill: skin },
    { x: 0, y: 0, w: 8, h: 2, fill: hair },
    { x: 0, y: 2, w: 1, h: 2, fill: hair },
    { x: 7, y: 2, w: 1, h: 2, fill: hair },
    { x: 1, y: 4, w: 2, h: 1, fill: "#FFFFFF" },
    { x: 2, y: 4, w: 1, h: 1, fill: eye },
    { x: 5, y: 4, w: 2, h: 1, fill: "#FFFFFF" },
    { x: 5, y: 4, w: 1, h: 1, fill: eye },
    { x: 3, y: 5, w: 2, h: 1, fill: shade(skin, 0.14) },
    { x: 2, y: 6, w: 4, h: 1, fill: shade(skin, 0.42) },
  ];
  if (fringe) pixels.push({ x: 1, y: 2, w: 2, h: 1, fill: hair }, { x: 6, y: 2, w: 1, h: 1, fill: hair });
  return pixels.map((pixel, index) => ({ key: String(index), ...pixel }));
}
