import { COLORS } from "../../engine/constants";
import type { Pitfall } from "../../engine/types";
import { CardShell, Row } from "./CardShell";
import { SceneCard } from "./anim";

export const PitfallCard: React.FC<{ p: Pitfall; durFrames: number }> = ({ p, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip="易错点" accent={COLORS.warn}>
      <div style={{ fontSize: 46, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>✗ {p.claim}</div>
      <Row label="为什么" text={p.why} color={COLORS.warn} />
    </CardShell>
  </SceneCard>
);
