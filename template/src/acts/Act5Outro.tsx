import { AbsoluteFill, interpolate, useCurrentFrame } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

const typed = (text: string, f: number, startF: number) =>
  text.slice(0, Math.max(0, Math.floor((f - startF) / 2)));

export const Act5Outro: React.FC<{ durFrames: number }> = ({ durFrames }) => {
  const f = useCurrentFrame();
  const out = interpolate(f, [durFrames - 20, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const l1 = typed("WhatsYourFuture出品", f, 6);
  const l2 = typed("祝你一战上岸", f, 46);
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.white, justifyContent: "center",
      alignItems: "center", opacity: out }}>
      <div style={{ textAlign: "center" }}>
        <div style={{ fontSize: 92, fontWeight: 800, color: COLORS.ink, fontFamily: FONT_FAMILY,
          minHeight: 120 }}>
          {l1}<span style={{ display: "inline-block", width: 8, height: 80, backgroundColor: COLORS.accent,
            marginLeft: 6, opacity: f % 20 < 10 ? 1 : 0 }} />
        </div>
        <div style={{ marginTop: 24, fontSize: 56, color: COLORS.sub, minHeight: 80 }}>
          {l2}{l2.length > 0 && l2.length < 6 ? "_" : ""}
        </div>
      </div>
    </AbsoluteFill>
  );
};
