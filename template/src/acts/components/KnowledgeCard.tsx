import { COLORS } from "../../engine/constants";
import type { Knowledge } from "../../engine/types";
import { Katex } from "./Katex";
import { CardShell, Row } from "./CardShell";
import { SceneCard } from "./anim";

export const KnowledgeCard: React.FC<{ k: Knowledge; durFrames: number }> = ({ k, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip="考点">
      <div style={{ fontSize: 44, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>{k.claim}</div>
      <div style={{ marginTop: 26 }}><Katex tex={k.formula} fontSize={58} color={COLORS.accent} /></div>
      <Row label="前提" text={k.premise} color={COLORS.sub} />
      <Row label="易忽略" text={k.trap} color={COLORS.warn} />
    </CardShell>
  </SceneCard>
);
