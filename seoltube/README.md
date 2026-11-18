# seoltube

인기 음악 차트(Korea)를 보고, 영상을 보고, 소리를 mp3로 받는 가벼운 안드로이드 앱.

- 화면(앱)과 엔진을 나눈다. 엔진은 [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)(GPL-3.0)를 쓴다.
- 엔진은 GitHub Actions(`.github/workflows/seoltube-engine.yml`)가 매일 새 버전을 확인해 자동으로 만들고, JVM 에서 검색과 영상 정보를 시험한 뒤 릴리스에 올리고, `engine/latest.json`에 적는다. 앱은 하루 한 번 이것을 보고 받아 갈아 끼운다.
- 엔진은 서명되어 있다. `latest.json`의 `sig`는 엔진 파일의 서명, `msig`는 엔진 번호, api, sha256, 크기의 서명이다. 앱은 둘 다 맞는 엔진만 쓴다.

## 폴더

- `engine/` 엔진 소스. 앱은 `com.seolpc.seoltube.engine.Engine` 의 `call(String)` 하나만 부른다(요청과 응답은 JSON).
- `engine/smoke/` 올리기 전 시험(엔진 소스를 같은 NewPipe 판으로 JVM 에서 돌린다). `gradle -p seoltube/engine/smoke run`
- `engine/latest.json` 지금 엔진(자동 빌드가 씀).
