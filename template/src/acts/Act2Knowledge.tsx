import { Sequence } from "remotion";
import type { Layout } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { KnowledgeCard } from "./components/KnowledgeCard";

export const Act2Knowledge: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => (
  <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
    width: layout.main.w, height: layout.main.h }}>
    {timeline.scenes
      .filter((w) => w.act === 2 && w.component === "knowledge-card")
      .map((w) => (
        <Sequence key={w.sceneId} from={w.startFrame} durationInFrames={w.durFrames}>
          <KnowledgeCard k={content.knowledge[w.props.knowledgeRef! - 1]} durFrames={w.durFrames} />
        </Sequence>
      ))}
  </div>
);
