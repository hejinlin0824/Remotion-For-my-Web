import { Sequence, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, EXIT_F } from "../../engine/constants";
import type { MethodItem } from "../../engine/types";
import { CardShell, Row } from "./CardShell";

const Item: React.FC<{ item: MethodItem; index: number; active: boolean; localStart: number }> =
({ item, index, active, localStart }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - localStart, fps, config: { damping: 15, stiffness: 120 }, durationInFrames: 14 });
  if (s <= 0) return null;
  return (
    <div style={{ opacity: active ? 1 : 0.6 * s, borderLeft: active ? `8px solid ${COLORS.hl}` : "8px solid transparent",
      paddingLeft: 20, transform: `translateY(${(1 - s) * 24}px)` }}>
      <div style={{ fontSize: 34, fontWeight: 700, color: active ? COLORS.hl : COLORS.text, marginTop: 14 }}>
        {index + 1}. {item.step}
      </div>
      <Row label="套路" text={item.trick} color={COLORS.sub} />
    </div>
  );
};

export const GeneralList: React.FC<{ items: MethodItem[]; activeIndex: number;
  starts: number[]; span: number }> = ({ items, activeIndex, starts, span }) => {
  const f = useCurrentFrame();
  const exit = interpolate(f, [span - EXIT_F, span], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <div style={{ opacity: exit }}>
      <CardShell chip="通法总结" accent={COLORS.hl}>
        {items.map((item, i) => (
          <Item key={i} item={item} index={i} active={i === activeIndex} localStart={starts[i]} />
        ))}
      </CardShell>
    </div>
  );
};
