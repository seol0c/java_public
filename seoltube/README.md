# seoltube

인기 음악 차트(Korea)를 보고, 영상을 보고, 소리를 mp3로 받는 가벼운 안드로이드 앱.

- 화면(앱)과 엔진을 나눈다. 엔진은 [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)(GPL-3.0)를 쓴다.
- 엔진은 GitHub Actions(`.github/workflows/seoltube-engine.yml`)가 매일 새 버전을 확인해 자동으로 만들어 릴리스에 올리고, `engine/latest.json`에 적는다. 앱은 하루 한 번 이것을 보고 받아 갈아 끼운다.
- 엔진은 서명되어 있고, 앱은 서명이 맞는 엔진만 쓴다.

## 폴더

- `engine/` 엔진 소스. 앱은 `com.seolpc.seoltube.engine.Engine` 의 `call(String)` 하나만 부른다(요청과 응답은 JSON).
- `engine/latest.json` 지금 엔진(자동 빌드가 씀).
