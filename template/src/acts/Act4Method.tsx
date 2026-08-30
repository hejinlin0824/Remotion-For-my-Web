import { Sequence, useCurrentFrame } from "remotion";
import type { Layout } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { GeneralList } from "./components/GeneralList";

export const Act4Method: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const scenes = timeline.scenes.filter((w) => w.act === 4);
  const start = scenes[0].startFrame;
  const lastEnd = scenes[scenes.length - 1].startFrame + scenes[scenes.length - 1].durFrames;
  const span = lastEnd - start;
  const shown = scenes[scenes.length - 1].props.itemRef!;   // 末场时列表应完整
  return (
    <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
      width: layout.main.w, height: layout.main.h }}>
      <Sequence from={start} durationInFrames={span}>
        <Act4Inner content={content} timeline={timeline} span={span} shown={shown} start={start}
          availWidth={layout.main.w} availHeight={layout.main.h} />
      </Sequence>
    </div>
  );
};

const Act4Inner: React.FC<{ content: ContentJson; timeline: Timeline; span: number;
  shown: number; start: number; availWidth: number; availHeight: number }> =
({ content, timeline, span, shown, start, availWidth, availHeight }) => {
  const f = useCurrentFrame();
  const scenes = timeline.scenes.filter((w) => w.act === 4);
  let activeIndex = 0;
  for (let i = 0; i < scenes.length; i++) {
    if (f + start >= scenes[i].startFrame) activeIndex = scenes[i].props.itemRef! - 1;
  }
  return (
    <GeneralList items={content.generalMethod.slice(0, shown)} activeIndex={activeIndex}
      starts={scenes.map((w) => w.startFrame - start)} span={span}
      availWidth={availWidth} availHeight={availHeight} />
  );
};
