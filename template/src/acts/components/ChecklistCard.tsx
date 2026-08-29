import { spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS } from "../../engine/constants";
import type { Pitfall } from "../../engine/types";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const ChecklistCard: React.FC<{ pitfalls: Pitfall[]; durFrames: number }> =
({ pitfalls, durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <SceneCard durFrames={durFrames}>
      <CardShell chip="检查清单" accent={COLORS.ok}>
        {pitfalls.map((p, i) => {
          const s = spring({ frame: f - 10 - i * 8, fps, config: { damping: 15, stiffness: 130 }, durationInFrames: 12 });
          return (
            <div key={i} style={{ display: "flex", gap: 18, alignItems: "center", marginTop: 20, opacity: s }}>
              <span style={{ fontSize: 40, color: COLORS.ok, fontWeight: 800 }}>✓</span>
              <span style={{ fontSize: 38, color: COLORS.text }}>{p.claim}</span>
            </div>
          );
        })}
      </CardShell>
    </SceneCard>
  );
};
