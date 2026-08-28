import "katex/dist/katex.min.css";
import { AbsoluteFill, Audio, Sequence, staticFile } from "remotion";
import { CHAPTER_F, COLORS, FONT_FAMILY } from "../engine/constants";
import { LAYOUTS } from "../engine/layout";
import { audioMeta, content, timeline } from "../engine/data";
import { Act1Intro } from "./Act1Intro";
import { Act5Outro } from "./Act5Outro";
import { ProblemStage } from "./ProblemStage";
import { Act2Knowledge } from "./Act2Knowledge";
import { ChapterTitle } from "./components/ChapterTitle";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => {
  // 布局由 composition 参数决定（一套内容两个 composition 都能渲）；
  // content.meta.aspect 只是请求画幅记录，Phase 2 的 V1 负责与任务参数核对，渲染层不做 throw。
  const layout = LAYOUTS[aspect];
  const act1 = timeline.acts[0], act5 = timeline.acts[4];
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY }}>
      {/* 幕1 固定片头 */}
      <Sequence durationInFrames={act1.durFrames}>
        <Act1Intro durFrames={act1.durFrames} />
        <Audio src={staticFile(audioMeta.fixed.act1.file)} />
      </Sequence>

      {/* 幕2-4 */}
      <ProblemStage content={content} timeline={timeline} layout={layout} />
      <Act2Knowledge content={content} timeline={timeline} layout={layout} />
      {/* Act3 / Act4: Task 10 / 11 */}
      {timeline.chapterSlots.map((c) => (
        <Sequence key={c.act} from={c.fromFrame} durationInFrames={CHAPTER_F}>
          <ChapterTitle title={c.title} durFrames={CHAPTER_F} />
        </Sequence>
      ))}
      {timeline.scenes.map((w) => (
        <Sequence key={w.sceneId} from={w.startFrame} durationInFrames={w.durFrames}>
          <Audio src={staticFile(w.lineFile)} />
        </Sequence>
      ))}

      {/* 幕5 固定片尾 */}
      <Sequence from={act5.startFrame} durationInFrames={act5.durFrames}>
        <Act5Outro durFrames={act5.durFrames} />
        <Audio src={staticFile(audioMeta.fixed.act5.file)} />
      </Sequence>
    </AbsoluteFill>
  );
};
