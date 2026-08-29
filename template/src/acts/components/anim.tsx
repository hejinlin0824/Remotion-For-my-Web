import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import type { CSSProperties, ReactNode } from "react";
import { EXIT_F } from "../../engine/constants";

export const useEnter = (delay = 0) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - delay, fps, config: { damping: 16, stiffness: 110 }, durationInFrames: 16 });
  return { opacity: s, y: (1 - s) * 36 };
};

export const SceneCard: React.FC<{ durFrames: number; delay?: number; style?: CSSProperties; children: ReactNode }> =
({ durFrames, delay = 8, style, children }) => {
  const f = useCurrentFrame();
  const { opacity, y } = useEnter(delay);
  const exit = interpolate(f, [durFrames - EXIT_F, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ opacity: opacity * exit,
      transform: `translateY(${y + (1 - exit) * -24}px)`, ...style }}>
      {children}
    </AbsoluteFill>
  );
};
