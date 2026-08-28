export const FPS = 30;
export const BREATH_SEC = 0.18;
export const ACT5_TAIL_SEC = 2.0;
export const CHAPTER_SEC = 1.8;
export const CHAPTER_F = Math.round(CHAPTER_SEC * FPS);      // 54
export const TRANSITION_F = 18;                              // 题干全屏→左上角过渡
export const EXIT_F = 10;                                    // 组件退场帧数
export const COLORS = {
  bg: "#0E1220", bg2: "#171C30", card: "#1A2138", cardBorder: "#2A3352",
  accent: "#5B8DFF", text: "#F2F5FF", sub: "#9AA3C0", hl: "#FFD54A",
  ok: "#4ADE80", warn: "#F87171", white: "#FFFFFF", ink: "#0E1220",
} as const;
export const FONT_FAMILY = "'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', sans-serif";
export const CHAPTER_TITLES: Record<2 | 3 | 4, string> = { 2: "知识点回顾", 3: "本题解法", 4: "以后怎么做" };
