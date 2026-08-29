import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

export const Act1Intro: React.FC<{ durFrames: number }> = ({ durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f, fps, config: { damping: 20, stiffness: 80 }, durationInFrames: 12 });
  const out = interpolate(f, [durFrames - 15, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bg, justifyContent: "center", alignItems: "center" }}>
      <div style={{ opacity: s * out, textAlign: "center" }}>
        <div style={{ fontSize: 108, fontWeight: 800, color: COLORS.text, fontFamily: FONT_FAMILY }}>
          WhatsYourFuture<span style={{ color: COLORS.accent }}>帮你讲</span>
        </div>
        <div style={{ marginTop: 30, fontSize: 38, color: COLORS.sub, letterSpacing: 6 }}>
          WhatsYourFuture 版权所有
        </div>
      </div>
    </AbsoluteFill>
  );
};
