// src/Root.tsx — Task 1 占位版（durationInFrames 先写死，Task 11 换成 timeline）
import { Composition } from "remotion";
import { LectureVideo } from "./acts/LectureVideo";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Lecture169" component={LectureVideo} width={1920} height={1080} fps={30}
      durationInFrames={90} defaultProps={{ aspect: "16:9" }} />
    <Composition id="Lecture916" component={LectureVideo} width={1080} height={1920} fps={30}
      durationInFrames={90} defaultProps={{ aspect: "9:16" }} />
  </>
);
