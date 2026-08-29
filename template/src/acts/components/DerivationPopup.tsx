import { COLORS } from "../../engine/constants";
import { Katex } from "./Katex";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const DerivationPopup: React.FC<{ formula: string; note: string; durFrames: number }> =
({ formula, note, durFrames }) => (
  <SceneCard durFrames={durFrames} delay={4}
    style={{ justifyContent: "flex-end", paddingBottom: 8 }}>
    <CardShell chip="推演" accent={COLORS.warn}>
      <Katex tex={formula} fontSize={56} color={COLORS.hl} />
      <div style={{ marginTop: 16, fontSize: 30, color: COLORS.text, opacity: 0.9 }}>{note}</div>
    </CardShell>
  </SceneCard>
);
