import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, FONT_FAMILY } from "../../engine/constants";

export const ChapterTitle: React.FC<{ title: string; durFrames: number }> = ({ title, durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const scrim = interpolate(f, [0, 8, durFrames - 12, durFrames], [0, 0.55, 0.55, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const s = spring({ frame: f - 4, fps, config: { damping: 14, stiffness: 120 }, durationInFrames: 16 });
  const barW = interpolate(f, [8, 30], [0, 360], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ backgroundColor: `rgba(14,18,32,${scrim})`, justifyContent: "center",
      alignItems: "center", flexDirection: "column" }}>
      <div style={{ opacity: s, transform: `scale(${0.85 + 0.15 * s})`, textAlign: "center" }}>
        <div style={{ fontSize: 110, fontWeight: 800, color: COLORS.text, fontFamily: FONT_FAMILY,
          letterSpacing: 10 }}>{title}</div>
        <div style={{ height: 8, width: barW, backgroundColor: COLORS.accent, borderRadius: 4,
          margin: "26px auto 0" }} />
      </div>
    </AbsoluteFill>
  );
};
