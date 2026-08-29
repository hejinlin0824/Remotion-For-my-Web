import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, FONT_FAMILY } from "../../engine/constants";

// 章节大字蓝块转场：整块扫入 → 标题停留 → 整块扫出接下一幕。
// 无任何 opacity 淡出——扫出本身就是退场动画（末帧时块已完全在视口外，卸载不可见）。
export const ChapterTitle: React.FC<{ title: string; durFrames: number }> = ({ title, durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  // 扫入：[0,12] 从 -100% 到 0；扫出：[durFrames-12, durFrames] 从 0 到 100%。
  // 两区间在 54 帧下不重叠，中间帧 y=0（块静止铺满全屏）。
  const y = f < 12
    ? interpolate(f, [0, 12], [-100, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })
    : interpolate(f, [durFrames - 12, durFrames], [0, 100], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const s = spring({ frame: f - 12, fps, config: { damping: 14, stiffness: 120 }, durationInFrames: 16 });
  const barW = interpolate(f, [14, 34], [0, 360], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.chapterBg, justifyContent: "center",
      alignItems: "center", flexDirection: "column", transform: `translateY(${y}%)` }}>
      <div style={{ opacity: s, transform: `scale(${0.85 + 0.15 * s})`, textAlign: "center" }}>
        <div style={{ fontSize: 110, fontWeight: 800, color: COLORS.white, fontFamily: FONT_FAMILY,
          letterSpacing: 10, textAlign: "center" }}>{title}</div>
        <div style={{ height: 8, width: barW, backgroundColor: COLORS.hl, borderRadius: 4,
          margin: "26px auto 0" }} />
      </div>
    </AbsoluteFill>
  );
};
