// src/Root.tsx — 正式版：时长来自 timeline（模块加载即校验契约，错则中止）
import { Composition } from "remotion";
import { LectureVideo } from "./acts/LectureVideo";
import { timeline } from "./engine/data";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Lecture169" component={LectureVideo} width={1920} height={1080} fps={30}
      durationInFrames={timeline.totalFrames} defaultProps={{ aspect: "16:9" }} />
    <Composition id="Lecture916" component={LectureVideo} width={1080} height={1920} fps={30}
      durationInFrames={timeline.totalFrames} defaultProps={{ aspect: "9:16" }} />
  </>
);
