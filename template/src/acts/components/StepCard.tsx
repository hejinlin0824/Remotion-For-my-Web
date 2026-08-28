import { COLORS } from "../../engine/constants";
import type { Step } from "../../engine/types";
import { Katex } from "./Katex";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const StepCard: React.FC<{ step: Step; index: number; total: number; durFrames: number }> =
({ step, index, total, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip={`第 ${index} 步 / 共 ${total} 步`}>
      <div style={{ fontSize: 44, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>
        {step.statement}
      </div>
      <div style={{ marginTop: 24, backgroundColor: COLORS.bg2, borderRadius: 16, padding: "20px 28px" }}>
        <Katex tex={step.derivation} fontSize={54} color={COLORS.accent} />
      </div>
      <div style={{ marginTop: 20, fontSize: 30, color: COLORS.sub }}>
        <span style={{ color: COLORS.hl, fontWeight: 700 }}>注 </span>{step.note}
      </div>
    </CardShell>
  </SceneCard>
);
