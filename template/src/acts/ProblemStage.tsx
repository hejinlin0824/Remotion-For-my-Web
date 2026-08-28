import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { TRANSITION_F } from "../engine/constants";
import type { Layout, Rect } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { ProblemPanel } from "./components/ProblemPanel";

export const ProblemStage: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = timeline.problemScene;
  const pStart = p.startFrame, pEnd = p.startFrame + p.durFrames;
  const act4Start = timeline.act4StartFrame;
  if (frame < pStart || frame > act4Start + TRANSITION_F) return null;

  const design = layout.problemFull, corner = layout.corner;
  const t = interpolate(frame, [pEnd - TRANSITION_F, pEnd], [0, 1],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const rect: Rect = {
    x: design.x + (corner.x - design.x) * t,
    y: design.y + (corner.y - design.y) * t,
    w: design.w + (corner.w - design.w) * t,
    h: design.h + (corner.h - design.h) * t,
  };
  const enter = spring({ frame: frame - pStart, fps, config: { damping: 18, stiffness: 90 }, durationInFrames: 14 });
  const exitFade = interpolate(frame, [act4Start, act4Start + TRANSITION_F], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  const cur = timeline.scenes.find((s) => frame >= s.startFrame && frame < s.startFrame + s.durFrames);
  const anchor = cur?.stepAnchor;

  return (
    <div style={{ position: "absolute", left: rect.x, top: rect.y, width: rect.w, opacity: enter * exitFade }}>
      <div style={{ width: design.w, transform: `scale(${rect.w / design.w})`, transformOrigin: "top left" }}>
        <ProblemPanel content={content} highlightId={anchor} compact={t === 1} />
      </div>
    </div>
  );
};
