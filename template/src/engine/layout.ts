import type { AspectKey } from "./types";

export interface Rect { x: number; y: number; w: number; h: number }
export interface Layout {
  width: number; height: number;
  full: Rect;         // 全屏
  problemFull: Rect;  // 题干全屏展示区
  corner: Rect;       // 题干左上角常驻区
  main: Rect;         // 幕2/3/4 卡片主区
}
export const LAYOUTS: Record<AspectKey, Layout> = {
  "16:9": { width: 1920, height: 1080,
    full: { x: 0, y: 0, w: 1920, h: 1080 },
    problemFull: { x: 260, y: 220, w: 1400, h: 640 },
    corner: { x: 48, y: 44, w: 600, h: 300 },
    main: { x: 720, y: 120, w: 1140, h: 860 } },
  "9:16": { width: 1080, height: 1920,
    full: { x: 0, y: 0, w: 1080, h: 1920 },
    problemFull: { x: 70, y: 420, w: 940, h: 760 },
    corner: { x: 44, y: 44, w: 992, h: 380 },
    main: { x: 60, y: 500, w: 960, h: 1280 } },
};
