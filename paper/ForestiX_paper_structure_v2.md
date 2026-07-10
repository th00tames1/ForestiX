---
title: "ForestiX: A Cross-Platform Mensuration Application with User-Selectable DBH Pipelines and Per-Measurement Uncertainty for Smartphone Forest Inventory"
---

Both files read. Emitting the full revised structure document.


---

# Abstract

- **P1 (배경·문제).** 산림 인벤토리의 1차 측정량인 DBH와 tree height가 basal area, quadratic mean diameter, Lorey's mean height를 거쳐 aboveground biomass·carbon 추정으로 전파되므로 개별목 측정 정확도가 임분·탄소 추정 신뢰성을 좌우함을 한 문장으로 제시한다(Chave2014; Liang2016). 이어 전통적 diameter tape·hypsometer 측정은 정확하나 노동집약적이며, 스마트폰-LiDAR 앱(특히 ForestScanner)이 접근성을 크게 높였으나 iOS-LiDAR 단일 플랫폼에 묶이고 대체로 단일 점추정만 제공한다는 공백을 배경으로 둔다(Tatsumi2023; Ficko2020).

- **P2 (기여 — ForestiX가 main contribution).** 본 연구의 주 기여가 ForestiX라는 cross-platform 측정 애플리케이션임을 명시한다: iOS(ARKit)·Android(ARCore)에서 1:1 미러링된 공유 측정 엔진 위에 DBH·height·crown·plot·distance 측정을 통합하고, 사용자 선택형 네 가지 DBH 경로(LiDAR depth + RANSAC/Taubin circle fit, chord/silhouette, AR motion/VIO, AR caliper)와 walk-off tangent height $H = d_h(\tan\alpha_{top}-\tan\alpha_{base})$를 제공한다(Tatsumi2023). circle-fit·VIO·chord 경로는 GUM 기반으로 전파된 σ와 green/yellow/red 신뢰 등급을 동반하며, 통합 cruise→volume→export 계층은 현재 iOS에 구현되어 있고 Android는 CSV/bundle export를 제공함을 함께 적는다(GUM2008).

- **P3 (평가 = operating envelope).** 두 번째 축으로, 같은 트리 위에서 교환 실행되는 네 DBH 경로와 두 height distance-source(scene-mesh raycast vs estimated-plane raycast)를 비교하는 평가가 거리·입사각·arc-coverage·bark class 등 운용 조건에서 비-LiDAR 경로가 LiDAR 및 기준기기 허용오차 내로 일치하는 operating envelope를 규정함을 명시한다(Borz2024; Schuirmann1987). 평가 설계는 Tier 1(통제 타깃)→Tier 2(known-diameter cylinder)→Tier 3(field) bottom-up이며, diameter tape·Vertex를 정량화된 반복성 오차를 가진 기준기기로 다룬다(Luoma2017; Larjavaara2013).

- **P4 (분석 계획·전망).** 계획된 분석셋(measured-vs-reference scatter, Bland–Altman limits of agreement, equivalence/TOST 기반 operating-envelope 매핑, predicted σ vs observed |error| 기반 σ-calibration, felled-subsample 앵커 height agreement)을 열거하고(BlandAltman1986; Lin1989; Schuirmann1987), single-device·single-platform 측정은 device-confounded이므로 population 수준 iOS>Android 주장은 배제됨을 적는다. cross-platform·불확실성 인식 cruise 도구로서의 활용 전망으로 마무리한다(VIObench2022).

---

## Draftable abstract (English — for co-author review; word count to be confirmed at copy-edit)

Diameter at breast height (DBH) and tree height propagate through basal area, quadratic mean diameter, and Lorey's mean height into aboveground biomass and carbon estimates, so per-tree measurement accuracy governs the reliability of stand- and carbon-level inventory (Chave2014; Liang2016). Smartphone-LiDAR applications have markedly improved accessibility (Tatsumi2023), yet they remain tied to the iOS-LiDAR platform and typically report a single point estimate without per-measurement uncertainty (Ficko2020). We present **ForestiX**, a cross-platform mensuration application whose measurement engine is mirrored 1:1 across iOS (ARKit) and Android (ARCore), integrating individual-tree DBH, height, crown, plot, and distance measurement. ForestiX offers four user-selectable DBH pipelines — LiDAR depth with RANSAC/Taubin circle fit, chord/silhouette, AR-motion (VIO), and AR caliper — and a walk-off-tangent height path $H = d_h(\tan\alpha_{top}-\tan\alpha_{base})$. The circle-fit, VIO, and chord pipelines carry a propagated standard uncertainty and a green/yellow/red confidence tier (GUM2008); an integrated cruise, volume, and export layer is implemented on iOS, with CSV/bundle export on Android. As an evaluation, the interchangeable pipelines are compared on the same trees to delineate an *operating envelope* — the conditions of distance, incidence, arc coverage, and bark class under which non-LiDAR paths are expected to agree with LiDAR and reference instruments within field tolerance (Borz2024; Schuirmann1987). We describe a bottom-up design (controlled targets → known-diameter cylinders → field stands) and planned analyses (limits of agreement, equivalence testing, uncertainty calibration), treating diameter tape and hypsometer as instruments with quantified error (Luoma2017; Larjavaara2013).

---

## Keywords

forest inventory; DBH; smartphone LiDAR; ARCore / visual-inertial odometry; measurement uncertainty; operating envelope

---

# 1. Introduction

## 1.1 Need — why per-tree DBH and height accuracy and accessibility matter

- **P1 (need / stakes).** 산림 인벤토리의 1차 측정량인 DBH와 수고가 단순 기록이 아니라 임분·탄소 추정의 입력 변수로 상향 전파됨을 서술한다. 개별목 DBH·height·wood density 기반 allometry가 aboveground biomass(AGB) 추정의 표준 경로이며(Chave2014), 개별목 측정이 basal area, quadratic mean diameter, Lorey's mean height를 거쳐 임분·탄소 수준 추정으로 누적된다(Liang2016). 따라서 개별목 측정 단계의 bias·분산이 상위 추정의 신뢰성을 직접 좌우하며, 측정 정확도 확보가 인벤토리 품질의 출발점임을 제기한다.

- **P2 (현장 측정의 노동·접근성 병목 + 기준기기 자체 오차).** 전통적 diameter tape·hypsometer/Vertex 기반 현장 측정이 표준적이고 정확하지만 노동집약적이며 인력·시간 비용이 크다는 운용적 병목을 서술한다. 이와 함께 기준기기들이 정량화된 반복성 오차를 가진 측정기기임을 명시하여 — DBH 반복측정 표준편차 약 0.3 cm(약 1.5%), 수고 약 0.5 m(약 2.9%)(Luoma2017)와 tangent·sine 방법 간 수고 측정 차이(Larjavaara2013) — 모든 비교가 정량화된 오차를 가진 기기 간 일치도(agreement) 문제임을 도입부 시작부터 프레이밍한다.

## 1.2 Prior approaches and their limits

- **P3 (TLS/MLS: 정확하나 비용·운용 장벽).** Terrestrial/mobile laser scanning이 개별목 stem 추출과 DBH 산출에서 사실상 reference 역할을 수행해 왔음을 정리한다(Liang2016). 그러나 TLS/MLS는 장비 비용·중량·후처리 부담으로 일상적 cruise 도구로는 제약이 크며, 저비용 mobile mapping이 그 대안으로 모색되어 왔다(Mokros2021). 또한 stem fitting에서 circle/cylinder fitting 및 scan mode 선택이 추출된 DBH·volume에 영향을 주며(Liang2013), RANSAC 기반 stem denoising·modelling 알고리즘 선택이 단일목 point cloud 결과를 좌우한다(deConto2017). 즉 laser 기반 reference도 처리 선택에 의존하는 추정임을 분명히 한다.

- **P4 (스마트폰 LiDAR 앱: 접근성 도약과 그 경계).** 소비자용 iPhone/iPad LiDAR의 등장으로 저비용·실시간 측정 앱 생태계가 형성되었음을 서술한다. ForestScanner가 LiDAR-equipped iPhone/iPad에서 실시간 circle-fit DBH와 매핑을 가능케 했고(Tatsumi2023), caliper 대비 DBH 평가가 누적되었으며(Gulci2023), 앱 간 비교·understory 효과(Howie2024), LiDAR-AR 앱의 breast-height DBH 정확도(Borz2024), plot 단위 인벤토리 적용(Gollob2021), boreal forest에서의 하한 직경 임계(약 5–10 cm)(Tatsumi_iPadBoreal2023), iPhone LiDAR ranging의 거리 한계(약 5 m)(Vinci2021_iphone), forest-type 의존성(Sandim2023)이 보고되었다. 그러나 이들은 (i) iOS-LiDAR 단일 플랫폼에 묶여 있고, (ii) 대체로 단일 속성(주로 DBH)에 집중되며, (iii) 앱별 내부 처리에 결과가 의존하고, (iv) 측정거리·understory·forest-type에 민감하다는 공통 한계를 가짐을 정리하며, 이 한계를 정성적 positioning matrix로 [Table 1]에 배치한다.

 **[Table 1. Qualitative positioning vs prior smartphone-LiDAR studies — device/platform, measured attribute(s), reference instrument, operating-condition dependencies (distance, understory, forest type, lower-diameter threshold), and uncertainty provision across Tatsumi2023, Gulci2023, Howie2024, Borz2024, Gollob2021, Sandim2023, Tatsumi_iPadBoreal2023, Vinci2021_iphone; the ForestiX row gives platform/attribute/uncertainty-provision without accuracy numbers.]**

- **P5 (비-LiDAR 경로의 잠재력과 미해결 질문).** LiDAR 없이도 metric-scale 개별목 측정이 원리적으로 가능함을 서술한다. ARCore depth-from-motion이 ToF 없이 단안 카메라 움직임으로 depth를 추정하고(Valentin2018; Du2020), 그 신뢰 범위가 약 0.5–5 m로 문서화되어 있으며(GoogleARCoreDepth), visual-inertial odometry가 IMU를 통해 metric scale을 관측 가능하게 한다(VIObench2022). 또한 image/plot 단위 스마트폰 멘서레이션(Vastaranta2015; Pitkanen2021)과 동일 iPhone point cloud에서도 알고리즘 선택이 결과를 좌우한다는 보고(iPhoneMultiAlgo2024)가 있다. 이러한 비-LiDAR 경로는 image·plot 단위이거나 알고리즘·앱별로 단편적으로 평가되어, "소비자용 비-LiDAR 경로가 어떤 운용 조건에서 LiDAR 및 기준기기 허용오차 내로 충분한가"라는 질문이 미해결로 남아 있음을 명시한다.

- **P6 (불확실성·신뢰 정보의 부재).** 대부분의 스마트폰 측정 앱이 단일 점추정만 제공하고 per-measurement 불확실성이나 신뢰 등급을 표출하지 않음을 서술한다. 측정 불확실성을 다룬 선행 평가가 존재하지만(Ficko2020), 이는 앱 외부의 통계적 평가이며, 측정 불확실성의 전파·표현에 대한 표준 틀(GUM2008)을 앱 내부에서 측정마다 σ를 전파해 사용자에게 표출하는 운용 틀로 구현하는 일은 mensuration 앱 수준에서 다루어지지 않았다. 이 운용화의 공백을 본 절의 세 번째 한계로 둔다.

## 1.3 The gap

- **P7 (gap 명시).** 위 한계를 세 축의 공백으로 압축한다: (i) iOS-LiDAR와 Android-ARCore를 가로지르는 단일 설계의 cross-platform 측정 도구의 부재(Tatsumi2023), (ii) 동일 트리 위에서 교환적으로 실행·비교 가능한 복수 DBH 센싱 경로의 부재(VIObench2022), (iii) per-measurement σ-전파와 신뢰 등급의 앱 내 운용화의 부재(Ficko2020). 이 세 요건이 한 시스템에서 동시에 충족된 사례가 없으며, 이로부터 P5에서 제기한 "비-LiDAR 경로가 언제 충분한가"라는 질문이 정량적으로 답해지지 못한 채 남아 있음을 못박는다.

## 1.4 The ForestiX concept and objective

- **P8 (ForestiX 개념).** 위 공백에 대응하는 시스템으로 ForestiX를 소개한다. iOS(ARKit)와 Android(ARCore)에서 1:1로 미러링된 공유 측정 엔진을 통해 DBH·height·crown·plot·distance를 측정하고(Tatsumi2023), 네 가지 사용자 선택형 DBH 경로와 GUM 기반 σ-전파·green/yellow/red 신뢰 등급(GUM2008)을 제공하며, 통합 cruise→volume→export 계층은 iOS에 구현되어 있음을 기술한다. 앱 자체가 본 연구의 main contribution이며, LiDAR-vs-비LiDAR 비교는 이 시스템 위에서 수행되는 평가·operating-envelope demonstration임을 분명히 한다.

 **[Figure 1. ForestiX cross-platform system architecture — iOS (ARKit) and Android (ARCore) sensor + UI layers over a 1:1-mirrored shared measurement engine, feeding the iOS cruise computation and export layer.]**


- **P9 (평가 철학 = operating envelope).** 비교의 목적을 한정한다. 목적은 거리·입사각·arc-coverage·bark class 등 운용 조건의 공간에서 비-LiDAR 경로가 LiDAR 및 기준기기 허용오차 내로 일치하는 operating envelope를 규정하는 데 있음을 서술한다(Borz2024). 평가 원칙으로, 기준기기는 정량화된 오차를 가진 기기로 다루며(Luoma2017; Larjavaara2013), 일치 여부는 analyst가 사전 설정한 equivalence margin에 대한 동치 검정으로 판정함을 명시한다(Schuirmann1987).

 **[Figure 2. Bottom-up evaluation design schematic — Tier 1 controlled targets × distance × incidence angle × surface; Tier 2 known-diameter cylinders; Tier 3 field stands.]**

- **P10 (objective).** 본 연구의 목적을 세 절로 명시한다: (1) ForestiX의 cross-platform 아키텍처와 네 DBH 경로·VIO walk-off tangent height 경로·per-measurement 불확실성 프레임워크를 기술한다; (2) 통제 타깃 → known-diameter cylinder → 현장 stand로 이어지는 bottom-up 평가 설계와 분석 계획을 제시한다; (3) 비-LiDAR 경로가 LiDAR 및 기준기기와 통계적으로 동치인 distance × incidence × arc-coverage × bark class 조건, 즉 operating envelope를 매핑하는 것을 핵심 질문으로 둔다(Schuirmann1987; GUM2008). 분석 계획과 그 산출 그림 자리를 기술하며 현장 정확도 수치는 산출되는 단계가 아님을 한 차례 적는다.

---

# 2. Materials and Methods

## 2.1 System architecture and cross-platform implementation

- **P1 (아키텍처 개요 / 공유 엔진).** ForestiX가 iOS(SwiftUI + ARKit/RealityKit, 영속화 Core Data)와 Android(Kotlin + Jetpack Compose + ARCore via SceneView/Filament, 영속화 Room)의 두 네이티브 구현으로 빌드되며, 플랫폼별 센서·렌더·UI 계층 아래에서 DBH·height·crown·plot·distance 측정 알고리즘이 1:1로 미러링된 단일 공유 측정 엔진으로 수렴함을 기술한다([Figure 1]). 이 엔진 출력이 cruise·export 경로로 흘러 하나의 애플리케이션을 이루며, 통합 cruise 계층은 iOS에 구현됨을 명시한다. 앱 자체가 본 논문의 주 기여이고 후속 LiDAR-vs-비LiDAR 비교는 이 위에서 수행되는 평가임을 둔다(Tatsumi2023).

- **P2 (플랫폼 동등성과 그 분석적 경계).** 알고리즘 수준에서는 두 플랫폼이 미러링되지만 depth source, camera intrinsics, 일부 fit 파라미터가 플랫폼별로 다르므로, 단일 디바이스·단일 플랫폼으로 얻은 측정은 device-confounded임을 기술한다. 따라서 iOS·Android 차이를 plot 단위로 보고하더라도 이를 플랫폼 모집단 수준의 우열 주장으로 확장하지 않는다는 분석 원칙을 둔다. 차이의 원천인 VIO 안정성은 off-the-shelf VIO 벤치마크로 맥락화하며(VIObench2022), intrinsics·fit 파라미터 차이는 [Table 2]에서 경로별로 분리해 제시한다.

- **P3 (UI 및 측정 투명성 노출).** DBH scan, method picker, height, plot, 그리고 실시간 developer/research HUD를 포함한 사용자 흐름을 기술하고(UI montage는 Supplementary), 각 측정 화면이 raw diameter·distance·pitch·점 개수·σ·confidence tier를 현장에서 그대로 노출함을 기술한다. 이 노출이 측정 재현성·검증 가능성을 위한 장치이며, 동일 화면 구성이 양 플랫폼에서 미러링됨을 함께 둔다(Tatsumi2023).

## 2.2 The four DBH sensing pipelines

- **P4 (네 경로 개관 / 비교 집합).** 사용자가 같은 트리 위에서 교환적으로 실행할 수 있는 4개 DBH 경로 — (1) LiDAR depth + circle fit, (2) chord/silhouette(default), (3) AR motion/VIO, (4) AR caliper — 의 공통 흐름(입력 → fit/geometry → DBH + propagated σ)을 개관한다([Figure 3]). 이들이 동일 설계 하에서 직접 비교 가능한 comparison set이며, 경로 간 차이를 estimator·fit 파라미터·point source·intrinsics 단위로 [Table 2]에 명시함을 둔다(Tatsumi2023; deConto2017; Valentin2018).

 **[Figure 3. The four user-selectable DBH sensing pipelines as a 2×2 comparison panel (LiDAR depth circle-fit, chord/silhouette, VIO circle-fit, AR caliper) — each shown as input → fit/geometry → DBH + σ, with the distinguishing estimator highlighted per pipeline.]**

- **P5 (Pipeline 1 — LiDAR depth + circle fit).** iOS는 ARKit `sceneDepth`(약 256×192 다운샘플)를 핀홀 back-projection $X_c=(x-c_x)\,Z/f_x$로 world XZ 평면에 투영하고, 깊이 노이즈로 인한 outlier를 k-NN 기반 statistical outlier removal($k=8$, $\sigma_{mult}=2.0$)로 제거한 뒤, stratified 3-point Kåsa seed로 초기화한 RANSAC consensus와 Taubin refit으로 stem 단면 원을 적합해 radius·center·arc coverage·radial RMSE·$\sigma_R$를 산출함을 기술한다(Fischler1981; Kasa1976; Taubin1991). 결정론적 SplitMix64 RNG로 재현성을 확보하며, algebraic circle-fit 계열(Kåsa·Pratt·Taubin)은 stem 단면 적합의 표준 배경으로 둔다(Pratt1987; deConto2017; Liang2013).


- **P6 (Pipeline 2 — chord/silhouette, default).** 투영된 trunk 픽셀폭과 depth·focal로 직경을 얻는 chord/silhouette 경로를 기본값으로 두는 근거(부분 호·sparse depth에서도 안정적 단일-스칼라 추정)를 기술한다. iOS는 핀홀 half-width 형태 $d = w\,d_{tap}/(f_x - w/2)$를 사용하고, Android는 bbox-diagonal chord 변형을 사용하여 두 구현이 서로 다른 estimator 형태를 가지며, 이 차이는 정합·측정 대상으로 [Table 2]에 명시함을 기술한다(Tatsumi2023; Liang2013).

- **P7 (Pipeline 3 — AR motion / VIO).** LiDAR 없이 ARKit `rawFeaturePoints`를 약 3 s sweep 동안 world-frame으로 누적하는 경로를 기술하며, IMU가 visual-inertial odometry에서 metric scale을 관측 가능하게 만들어 depth 센서 없이도 절대 직경 추정이 가능함을 근거로 둔다(VIObench2022). breast-height trunk-band ROI(±약 0.15 m 수평·수직 밴드)로 점군을 필터한 뒤 Pipeline 1과 같은 RANSAC+Taubin을 적용하되 inlier tolerance·minimum inliers 등 fit 파라미터가 depth 경로와 다르며, 이 inter-arm 차이를 [Table 2]에 기록함을 기술한다(Valentin2018; Fischler1981; Taubin1991).

- **P8 (Pipeline 4 — AR caliper geometry).** trunk 양 가장자리를 화면에서 두 번 탭해 camera-ray 방향(iOS `makeRaycastQuery` / Android projection-inverse unprojection)을 얻고, 두 ray의 subtended angle $\alpha$와 AR estimated-plane raycast 거리 $d$로부터
$$D = \frac{2\,d\,\sin(\alpha/2)}{1-\sin(\alpha/2)}$$
로 직경을 산출하는 기하를 기술한다(Valentin2018). 이 경로가 depth·point cloud 없이 순수 AR 기하로 동작함이 비교 가치이며, 현재 caliper σ는 상수(distanceRelSigma 0.05) 기반의 비정보적 값으로 σ-calibration 분석에서 제외됨을 둔다.

 **[Figure 4. AR-caliper geometry — two tangent camera rays, subtended angle α, plane-raycast distance d, and the diameter identity $D = 2d\sin(\alpha/2)/(1-\sin(\alpha/2))$.]**

- **P9 (Android depth 경로 특성).** Android는 ARCore Depth API(`acquireDepthImage16Bits`)로 얻은 depth로 circle-fit과 chord를 수행하고 AR caliper를 포팅했음을 기술한다(Du2020). ARCore depth-from-motion의 신뢰 범위(약 0.5–5 m)와 ToF의 선택적 보강 특성이 iOS의 dedicated LiDAR와 source 특성에서 다르며, 이 차이가 operating envelope에서 거리 의존성으로 나타날 수 있음을 명시한다(GoogleARCoreDepth; Valentin2018).

- **P10 (inter-pipeline 투명성 표).** 네 경로가 estimator(circle fit vs chord vs AR geometry), fit 파라미터, point source/density, intrinsics에서 어떻게 다른지를 한 표로 명시하는 것이 비교의 공정성·해석 가능성의 전제임을 기술하고, 경로별 차이를 [Table 2]로 투명하게 제시한다(Liang2013; deConto2017; iPhoneMultiAlgo2024).

 **[Table 2. Inter-pipeline difference (transparency) table — estimator, fit parameters, point source/density, and intrinsics for each of the four DBH pipelines, including the iOS/Android chord-implementation difference and the VIO/depth fit-parameter difference.]**

## 2.3 Tree height — VIO walk-off tangent and uncertainty propagation

- **P11 (height 기하·식, distance-source contrast).** trunk base를 화면중앙 raycast(scene-mesh raycast 또는 AR estimated-plane raycast)로 앵커하고, 일정 거리 walk-back 후 IMU에서 base·top pitch 각을 캡처하여
$$H = d_h\,(\tan\alpha_{top} - \tan\alpha_{base})$$
로 수고를 산출하는 walk-off tangent 절차를 기술한다. scene-mesh arm과 AR-plane arm이 동일 식을 쓰고 오직 distance source만 다르므로, 두 arm 비교는 distance source 차이로 한정됨을 명시한다. tangent 법 자체의 오차 특성은 선행 비교에 근거를 둔다(Larjavaara2013).

 **[Figure 5. Height walk-off tangent geometry ($d_h,\alpha_{top},\alpha_{base},H$) — the LiDAR-mesh and AR-plane arms share the formula and differ only in distance source.]**

- **P12 (height σ-전파).** 거리·각도 입력의 불확실성을 GUM 기반 3항 분산전파
$$\sigma_H^2 = (\tan\alpha_{top}-\tan\alpha_{base})^2\,\sigma_d^2 + d_h^2\sec^4\!\alpha_{top}\,\sigma_\alpha^2 + d_h^2\sec^4\!\alpha_{base}\,\sigma_\alpha^2$$
로 합성함을 기술하고, $\sec^4$ 항으로 인해 tangent 법 오차가 키 큰 폐쇄 임관에서 급격히 증가한다는 선행 근거를 제시한다(GUM2008; Larjavaara2013). 따라서 height는 reference 대비 절대 정확도가 아니라 felled subsample 앵커 또는 distance-source 간 agreement로 다루는 검증 전략을 둔다(Sullivan2018).

## 2.4 Per-measurement uncertainty and confidence tiers

- **P13 (σ-전파 일반틀).** circle-fit·VIO·chord 측정이 fit residual과 기하 민감도로부터 합성한 propagated σ를 동반한다는 GUM 기반 일반 원리를 기술하고, 이 per-measurement 운용화가 단일 점추정만 제공하는 다수 스마트폰 앱 관행과 구별됨을 밝힌다(GUM2008). 측정 불확실성을 다룬 선행 앱 평가(Ficko2020)는 앱 외부의 통계적 평가로서 prior art임을 명시하고, ForestiX는 앱 내부에서 측정마다 σ를 전파·표출함을 둔다.

 **[Figure 6. Per-measurement uncertainty and confidence-tier framework — checks → combineChecks → green/yellow/red.]**

- **P14 (confidence-tier 로직).** `combineChecks`가 arc coverage, $\sigma_R/R$ ratio, radial RMSE, inlier/point count, tracking-stayed-normal 등의 reject·warn 체크를 결합해 등급을 산출하는 규칙 — 임의 reject 실패 시 red, warn $\geq 2$이면 red, warn $\geq 1$이면 yellow, 그 외 green — 을 명시한다(GUM2008). 이 규칙이 현장에서 거절·재측정 의사결정의 운용적 신호가 됨을 함께 기술한다.

## 2.5 Integrated cruise computation and outputs (iOS)

- **P15 (cruise 위계 → 통계 → export).** 통합 cruise 계층이 iOS에 구현되어 있으며 Project→Stratum→CruiseDesign→Plot→Tree 위계로 개별목 측정을 임분 통계로 집계함을 기술한다. volume은 algebraic equations(Bruce, Chambers-Foltz, Schumacher-Hall)과 table lookup, board-foot log rules(Scribner Decimal C, International ¼″, Doyle)로, stand statistics는 basal area, trees·ha⁻¹, quadratic mean diameter(QMD), mean ± SE ± 95% CI(Satterthwaite df), Lorey's mean height, expansion factor로 산출하며, export는 iOS에서 PDF/CSV/GeoJSON/ESRI Shapefile로, Android에서 CSV/bundle로 제공함을 기술한다. 즉 cruise·stand-statistics·Shapefile/GeoJSON 계층은 현재 iOS 전용이며 Android는 CSV/bundle export로 한정됨을 명시하여 cruise 계층의 cross-platform parity를 가정하지 않게 한다(Liang2016; Chave2014).

- **P16 (developer/research HUD).** depth source, scaled intrinsics, point count, raw diameter, distance, pitch, σ, tier를 실시간 표출하는 developer/research mode를 측정 투명성·재현성·디버깅 장치로 기술하고, 이 라이브 내부 노출이 경로 간 차이를 추적·기록하는 근거가 됨을 둔다. 평가용 research data-logging·export의 study-wiring은 아직 구현되지 않은 계획 항목으로, throughput·retake rate 등 운용 지표 산출이 이 logging 파이프라인에 의존함을 함께 둔다.

## 2.6 Evaluation design — bottom-up tiered validation (planned)

- **P17 (3-tier 설계 개관).** Tier 1(통제 타깃 × distance × incidence angle × surface), Tier 2(known-diameter cylinders/phantoms), Tier 3(field stands)로 이어지는 bottom-up 설계의 논리(통제 → 기지직경 → 현장)를 기술하고([Figure 2]), 이것이 비-LiDAR 경로가 LiDAR·field tolerance 내로 일치하는 operating envelope를 규정하는 골격임을 명시한다(Borz2024; Howie2024; Gollob2021). 이 캠페인은 설계 단계의 계획으로 제시된다.

- **P18 (study sites / sampling + factors).** 현장 stand·species·bark class·DBH class·표본 수($n$) 구성과, 각 트리에서 4개 DBH 경로 + height를 반복 측정하는 sampling 설계를 기술하고, 통제·교차되는 운용 인자(measurement distance, incidence/viewing angle, bark class, ambient lighting)를 명시한다([Table 3]). 이 인자들이 operating envelope의 축이 됨을 둔다(Sandim2023; Gollob2021).

 **[Table 3. Study sites / sampling design (planned) — stands, species, bark classes, DBH classes, crossed operating factors (distance, incidence angle, lighting), and per-cell sample size n.]**

- **P19 (reference 기기와 anchor).** diameter tape와 hypsometer/Vertex를 정량화된 반복성(DBH SD 약 0.3 cm·1.5%, height SD 약 0.5 m·2.9%)을 가진 기준기기로 운용함을 명시하고, 이 reference 반복성을 이후 limits of agreement 폭 해석에 noise floor로 반영함을 기술한다(Luoma2017). height는 tangent 법 한계로 인해 felled subsample로 절대 앵커하거나 distance-source 간 agreement로 한정하는 검증 전략을 둔다(Larjavaara2013; Sullivan2018).

## 2.7 Accuracy and statistical analysis (planned)

- **P20 (정확도 지표셋).** 경로별 measured-vs-reference DBH에 대해 bias, RMSE, %RMSE, $R^2$, concordance correlation coefficient(CCC), 그리고 Bland–Altman limits of agreement(평균차 $\pm 1.96\,\mathrm{SD}$)를 산출할 계획을 기술하고, CCC를 단순 상관과 달리 정확도·정밀도를 동시에 반영하는 일치도 지표로 채택하는 근거를 둔다(Lin1989; BlandAltman1986). 산출 표 자리를 [Table 4]로 둔다. limits of agreement는 평균차를 평균 직경에 회귀하여 size-dependent bias를 점검하고, reference 반복성 오차를 합치 구간에 환산해 관측 불일치 중 reference 노이즈 기여분을 분리한다(BlandAltman1986; Luoma2017).

 **[Table 4. *[planned]* Accuracy metrics by pipeline (raw / calibrated) — bias, RMSE, %RMSE, R², CCC, limits of agreement, with the reference-instrument repeatability annotated as a noise floor.]**

- **P21 (mixed model + equivalence/TOST).** 트리·plot·디바이스의 반복·군집 구조를 반영하기 위해 random effects를 포함한 mixed model로 경로·인자 효과를 추정할 계획과, operating envelope 규정을 위해 two one-sided tests(TOST) 기반 equivalence test로 비-LiDAR 경로가 사전 설정 margin 내에서 LiDAR·reference와 동치인 조건(distance × incidence/arc-coverage × bark class)을 매핑할 계획을 기술한다(Schuirmann1987). equivalence margin $\delta$는 analyst가 사전에 정하는 결정값이며, reference 반복성(Luoma2017)을 도달 가능 정확도의 noise floor로, 임분·탄소 전파 허용오차를 상한 기준으로 참고해 정당화함을 분리해 명시한다.

- **P22 (raw-vs-calibrated firewall).** per-project linear DBH calibration $D_{true}=\alpha+\beta D_{meas}$를 적용하되, calibration fit에 쓴 데이터와 validation 데이터를 분리하는 firewall(fit ⟂ validation) 원칙과 raw·calibrated 결과를 동시에 보고하는 원칙을 명시한다. 이로써 calibration이 정확도를 인위적으로 부풀리지 않게 함을 기술하며, 현재 calibration 계수는 placeholder로서 Tier-1 이후 확정되는 계획 항목임을 둔다(GUM2008; Lin1989).

- **P23 (uncertainty calibration & coverage).** predicted σ와 observed $|\text{error}|$의 관계 및 confidence tier별 coverage probability(예: 명목 신뢰구간이 실제로 오차를 포함하는 비율)로 σ-전파와 등급 로직을 검증할 계획을 기술하고, caliper σ가 상수라 이 분석에서 제외됨을 명시한다(GUM2008; Ficko2020). 이 calibration 결과가 green/yellow/red 등급의 현장 거절·재측정 가치를 뒷받침함을 height·DBH 결과와 연결한다.

---

# 3. Results and Discussion

> 본 절은 계획된 분석과 그 산출 그림 자리를 기술한다. 각 문단은 분석의 목적·산출 그림·해석 논리를 기술한다.

## 3.1 Controlled error decomposition (Tier 1 / Tier 2, planned)

- **P1 (Tier-1·Tier-2 오차 분해).** 통제 타깃(Tier 1: 평면·원통 표면 × distance × incidence angle × surface)과 known-diameter cylinder(Tier 2)에서 각 DBH 경로의 측정오차를 bias·variance·residual로 분해하여, fit residual(RANSAC inlier RMSE)·기하 민감도·depth 노이즈가 각각 총 오차에 어떻게 기여하는지를 거리·입사각 격자 위에서 평가할 계획을 기술한다(Borz2024; Howie2024). 통제 조건이므로 기지직경이 firewall 없이 직접 reference로 기능하며, 이 단계가 현장 변동(bark·understory·tracking) 도입 전 경로별 고유 오차 구조를 분리하는 기반임을 명시한다. depth 경로는 $\sigma_R$, chord 경로는 $d=w\,d_{tap}/(f_x-w/2)$의 픽셀폭·focal 민감도, caliper 경로는 $D=2d\sin(\alpha/2)/(1-\sin(\alpha/2))$의 거리·각도 민감도를 각각 분해축으로 둔다.

- **P2 (거리·입사각·arc-coverage 응답면).** Tier-1 격자에서 측정 거리·입사각·획득 arc-coverage에 따른 경로별 오차 응답면을 산출해, 비-LiDAR 경로(VIO, caliper, chord)가 어느 운용 구간에서 LiDAR depth 경로와 동등한 오차 수준에 드는지를 사전 식별할 계획을 기술한다. 기존 스마트폰-LiDAR 연구가 보고한 거리 의존성·하한 직경 임계와 같은 좌표축에서 ForestiX의 통제 응답을 배치하여 현장 단계 해석의 기준선으로 삼는다(Borz2024; GoogleARCoreDepth; Vinci2021_iphone). 이 응답면이 현장 정확도와 operating-envelope 매핑을 잇는 통제 기준선임을 명시한다.


## 3.2 Field DBH accuracy by pipeline — raw and calibrated (planned)

- **P3 (measured-vs-reference 산점, raw·calibrated 동시).** 현장(Tier 3)에서 경로별 measured-vs-reference DBH를 1:1 선과 함께 산점도로 제시하되, raw 측정과 per-project linear calibration $D_{true}=\alpha+\beta D_{meas}$ 적용 후 calibrated 측정을 나란히 보고할 계획을 기술한다([Figure 7]). calibration 계수는 검증 표본과 분리된 별도 표본에서만 적합(fit ⟂ validation firewall)하며, raw·calibrated를 함께 제시하여 calibration이 정확도를 과대평가하지 않게 한다(Gulci2023; Howie2024). 해석 축은 bias·기울기($\beta$의 1로부터의 이탈)·이분산이며, 경로별로 어느 직경 구간에서 계통편차가 발생하는지를 읽는다.

- **P4 (정확도 지표 표, 기준기기 노이즈 플로어 반영).** bias·RMSE·%RMSE·$R^2$·CCC·limits of agreement를 경로별·raw/calibrated별로 집계하는 표를 산출할 계획을 기술하고, CCC를 정확도+정밀도 동시 반영 일치도 지표로 채택하는 근거를 명시한다([Table 4])(Lin1989). diameter tape 자체가 약 0.3 cm(약 1.5%)의 반복성 오차를 가진 기준기기임을 RMSE·LoA 폭 해석에 noise floor로 반영하여, 도달 가능 정확도의 하한을 reference 반복성으로 정의한다(Luoma2017). caliper 경로는 σ가 상수라 정확도 표에는 포함되나 σ-calibration에서는 제외됨을 부기한다.

 **[Figure 7. DBH measured-vs-reference scatter with 1:1 line, by pipeline (raw and per-project-calibrated panels).]**

## 3.3 Operating envelope — equivalence over distance × arc × bark (planned)

- **P6 (동치 영역 매핑과 결정 규칙).** distance × incidence/arc-coverage × bark class 공간에서, 비-LiDAR 경로의 측정이 LiDAR 경로 및 현장 허용오차 내로 통계적으로 동치인 영역을 two one-sided tests(TOST)로 매핑할 계획을 기술한다([Figure 8])(Schuirmann1987). 결정 규칙은, analyst가 사전 설정한 equivalence margin $\delta$에 대해 차이의 90% CI가 $[-\delta,+\delta]$에 포함되는 조건 격자 셀을 *in-envelope*로, 일부만 포함되면 *marginal*, 벗어나면 *out*으로 판정하는 별도 라벨 집합을 쓴다(앱의 green/yellow/red 측정 등급과 구분되는 통계적 판정임을 명시). $\delta$는 reference 반복성(Luoma2017)을 noise floor로, 임분·탄소 전파 허용오차를 상한으로 참고해 analyst가 정함을 분리해 둔다.

- **P7 (선행 스마트폰-LiDAR 연구 대비 위치).** 도출된 동치 영역과 경계 조건(거리 상한, arc-coverage 하한, 거친 bark에서의 이탈)을 기존 스마트폰-LiDAR 연구가 보고한 거리·understory·forest-type 의존성 및 하한 직경 임계와 같은 축에서 대조해 ForestiX의 operating envelope를 선행 문헌 위에 배치할 계획을 기술한다(Tatsumi2023; Gulci2023; Howie2024; Borz2024; Gollob2021; Sandim2023; Tatsumi_iPadBoreal2023). ARCore depth-from-motion의 신뢰 범위(약 0.5–5 m)와 iPhone LiDAR ranging 한계 등 source 특성을 envelope 경계의 물리적 근거로 연결한다(GoogleARCoreDepth; Vinci2021_iphone).

 **[Figure 8. Operating-envelope map (planned) — TOST equivalence region over distance × incidence/arc-coverage × bark class, with in-envelope / marginal / out cells (a labeling distinct from the in-app measurement tier).]**

## 3.4 Height: distance-source contrast (planned)

- **P8 (scene-mesh vs AR-plane distance-source agreement, felled anchor·anchor-success rate).** 동일한 walk-off tangent 식 $H=d_h(\tan\alpha_{top}-\tan\alpha_{base})$ 하에서 오직 distance source만 다른 두 arm(scene-mesh raycast vs AR estimated-plane raycast)의 height agreement를 평가할 계획을 기술한다([Figure 9]). 절대 정확도 앵커로 felled subsample을 사용하고, felling이 불가한 표본에서는 agreement로만 한정한다(Larjavaara2013; Sullivan2018). 추가로 각 distance source의 base-anchor 성공률(anchor-success/availability rate)을 임관 폐쇄·하층 조건별로 집계하여, AR-plane 및 scene-mesh 거리원의 운용 가용성을 측정 가능한 결과로 보고할 계획을 둔다. 해석 축으로 tangent 법 오차가 키 큰 폐쇄 임관에서 증가하는 경향을 두어, 두 distance source 간 불일치가 거리·임관 조건에 따라 어떻게 갈리는지를 읽는다(Larjavaara2013).

 **[Figure 9. Height agreement: scene-mesh vs AR-plane distance source (felled-subsample anchor), with base-anchor success rate per distance source by canopy-closure stratum.]**

## 3.5 Uncertainty calibration and coverage (planned)

- **P9 (σ-calibration·coverage probability).** 측정마다 전파된 predicted σ를 관측 $|\text{error}|$ 및 coverage probability(예: $\pm 1.96\,\sigma$ 구간이 실제 reference를 포함하는 비율)에 대비하여 confidence-tier 프레임워크를 검증할 계획을 기술한다([Figure 10])(GUM2008). predicted σ가 관측 오차 산포를 과소/과대 추정하는지를 calibration plot으로 진단하고, green/yellow/red 등급이 실제 오차 크기와 단조적으로 정렬되는지를 점검한다. caliper 경로의 σ는 상수(distanceRelSigma 0.05)로 비정보적이므로 이 분석에서 제외하고, depth·VIO·chord 경로에 한정해 σ 전파식의 현장 적합성을 평가한다(Ficko2020).

- **P10 (tier의 운용적 가치).** σ-calibration 결과에 비추어 green/yellow/red 등급이 현장에서의 거절·재측정 의사결정에 주는 운용적 가치를 논할 계획을 기술한다. yellow/red로 표시된 측정을 재측정 또는 폐기했을 때 임분 수준 추정의 오차가 감소하는 정도를 사후 분석으로 평가하여, 등급이 단순 표시가 아니라 의사결정 도구로 기능함을 보인다(Ficko2020; GUM2008). 단일 점추정만 제공하는 기존 앱 관행과의 차이를 운용 가치 측면에서 위치시킨다.

 **[Figure 10. Uncertainty calibration (planned) — predicted σ vs observed |error| with coverage probability, by pipeline (caliper excluded); the confidence-tier framework of Figure 6 provides the tier-decision context.]**

## 3.6 Positioning against prior smartphone-LiDAR studies (planned)

- **P11 (선행 연구 대비 정량 위치).** ForestiX의 경로별 정확도·operating envelope·불확실성 운용을 대표 스마트폰-LiDAR 연구와 공통 좌표(측정 거리, DBH class, RMSE/bias, forest type, 하한 직경, 불확실성 제공 여부)에서 정량적으로 정리하는 비교표를 산출할 계획을 기술한다([Table 5]). ForestScanner(iOS-LiDAR circle-fit) 대비로 cross-platform·복수 경로·σ 운용의 차별점을, caliper 기준 정확도 위치를, LiDAR-AR 정확도 맥락을 각각 명시한다(Tatsumi2023; Gulci2023; Howie2024; Borz2024; Gollob2021; Sandim2023). 이 표가 정량 정확도 문헌 위에 ForestiX를 배치하는 장치이며, 단일 디바이스·플랫폼 비교이므로 플랫폼 모집단 우열 일반화는 포함하지 않음을 둔다.

 **[Table 5. *[planned]* Quantitative positioning vs prior smartphone-LiDAR studies — measurement distance, DBH class, reported accuracy (RMSE/bias), forest type, lower diameter limit, and uncertainty provision, populated with ForestiX results once the campaign is complete.]**

## 3.7 Operational considerations and limitations

- **P12 (운용 지표 — throughput·retake rate·impossible-tree).** 정확도와 별개로 도구의 운용성을 정량화할 계획을 기술한다: 트리당 측정 시간(throughput), tier가 yellow/red를 유발해 발생하는 retake rate, 측정 자체가 불가한 impossible-tree 사례(예: arc-coverage 부족, 근접 차폐, base-anchor 실패) 비율을 경로별로 집계한다. 이 지표들의 산출은 아직 구현되지 않은 research data-logging 파이프라인에 의존하며, 이 logging이 갖춰진 뒤 산출됨을 명시한다. throughput·retake rate가 전통적 diameter tape·hypsometer 대비 노동·접근성 이점을 정량화하는 축이며, retake rate와 정확도의 trade-off가 현장 채택 가능성을 좌우함을 둔다(Borz2024; Sandim2023).

- **P13 (한계).** 본 평가 설계의 한계를 사실로 기술한다: (i) 비교는 경로(pipeline) 비교이며 fit 파라미터·intrinsics·depth source가 경로별로 다르다; (ii) 단일 디바이스·플랫폼이므로 iOS·Android 결과는 device-confounded이고 플랫폼 모집단 주장이 불가하다; (iii) chord 경로는 iOS(pinhole half-width)와 Android(bbox-diagonal) 구현이 달라 정합 대상이다; (iv) caliper σ가 비정보적 상수이다; (v) 기준기기(diameter tape·Vertex) 자체가 정량화된 오차를 가져 reference도 오차를 동반한다; (vi) ARCore Depth API는 버전·디바이스 의존성이 있어 software-depth 결과 일반화에 제약이 있다(VIObench2022; Luoma2017; Larjavaara2013; GoogleARCoreDepth).

- **P14 (다음 단계).** 한계를 닫기 위한 후속 단계를 기술한다: Tier-1→Tier-3 캠페인 완료, σ-calibration의 실측 적합, chord 경로 iOS·Android 정합, research-logging 파이프라인을 통한 재현 가능한 데이터 축적, 다기종·다플랫폼 확장을 통한 device-confound 완화. 이 단계들이 operating envelope를 모집단 수준으로 확장하고 cross-platform·불확실성 인식 cruise 도구로서의 일반화를 가능케 함을 둔다(deConto2017; iPhoneMultiAlgo2024).

---

# 4. Conclusion

- **P1 (요지 — 기여 재확인).** ForestiX가 iOS(ARKit)와 Android(ARCore)를 1:1로 미러링한 단일 설계의 측정 애플리케이션으로서, 사용자 선택형 4개 DBH 경로(LiDAR depth+circle fit, chord/silhouette, AR motion/VIO, AR caliper), VIO walk-off tangent height, GUM 기반 per-measurement σ-전파(circle-fit·VIO·chord)와 green/yellow/red 신뢰 등급을 하나의 도구로 통합했음을 요약한다([Figure 1]). 통합 cruise→volume→export 계층은 현재 iOS에 구현되어 있고 Android는 CSV/bundle export를 제공함을 함께 적는다. 본 논문의 주 기여가 LiDAR/non-LiDAR 비교가 아니라 이 cross-platform application이며 비교는 그 위의 평가임을 분명히 한다(Tatsumi2023; Valentin2018; GUM2008).

- **P2 (평가·operating envelope·σ-calibration 프레이밍).** Tier 1(controlled targets × distance × incidence × surface) → Tier 2(known-diameter cylinders) → Tier 3(field)로 이어지는 bottom-up 설계, equivalence test(TOST) 기반 operating-envelope 매핑, predicted σ 대 observed |error|·coverage 기반 σ-calibration이, 비-LiDAR 경로가 정량화된 오차를 가진 기준기기 및 LiDAR 경로의 field tolerance 내로 일치하는 운용 조건을 규정하기 위한 평가 틀임을 기술한다([Figure 2]). 이들이 계획된 분석임을 둔다(Borz2024; Schuirmann1987; Luoma2017; GUM2008).

- **P3 (실무 함의·전망).** 측정마다 σ와 신뢰 등급이 동반된 경로들이 commodity smartphone 위에서 저비용·접근성 높은 mensuration을 가능케 하며, operating envelope가 규정되면 어떤 거리·입사각·bark class 조건에서 non-LiDAR가 충분한지를 현장 의사결정에 직접 제공할 수 있고, 이것이 DBH·height→basal area·QMD·Lorey's mean height·AGB 전파의 신뢰성으로 이어짐을 기술한다(Chave2014; Liang2016). Tier 1–3 캠페인과 σ-calibration·chord 정합 완료 후 cross-platform·불확실성 인식 cruise 도구로서의 활용 전망으로 마무리한다.

---

# References (used)

- **BlandAltman1986** — Bland J.M., Altman D.G. (1986). Statistical methods for assessing agreement between two methods of clinical measurement. *Lancet* 327(8476):307–310.
- **Borz2024** — Borz S.A., Toaza J.M.M., Proto A.R. (2024). Accuracy of two LiDAR-based augmented reality apps in breast height diameter measurement. *Ecological Informatics* 81:102550. doi:10.1016/j.ecoinf.2024.102550.
- **Chave2014** — Chave J. et al. (2014). Improved allometric models to estimate the aboveground biomass of tropical trees. *Global Change Biology* 20(10):3177–3190. doi:10.1111/gcb.12629.
- **deConto2017** — de Conto T. et al. (2017). Performance of stem denoising and stem modelling algorithms on single tree point clouds from terrestrial laser scanning. *Computers and Electronics in Agriculture* 143:165–176. doi:10.1016/j.compag.2017.10.019.
- **Du2020** — Du R. et al. (2020). DepthLab: Real-time 3D interaction with depth maps for mobile AR. *UIST 2020*. doi:10.1145/3379337.3415881.
- **Ficko2020** — Ficko A. (2020). Bayesian evaluation of smartphone applications for forest inventories in small forest holdings. *Forests* 11(11):1148. doi:10.3390/f11111148.
- **Fischler1981** — Fischler M.A., Bolles R.C. (1981). Random sample consensus (RANSAC). *Communications of the ACM* 24(6):381–395. doi:10.1145/358669.358692.
- **Gollob2021** — Gollob C., Ritter T., Nothdurft A. (2021). Measurement of forest inventory parameters with Apple iPad Pro and integrated LiDAR technology. *Remote Sensing* 13(16):3129. doi:10.3390/rs13163129.
- **GoogleARCoreDepth** — Google ARCore Depth API developer documentation (depth-from-motion; reliable ~0.5–5 m; ToF optional).
- **Gulci2023** — Gülci S., Yurtseven H., Akay A.O., Akgül M. (2023). Measuring tree diameter using a LiDAR-equipped smartphone: a comparison of smartphone- and caliper-based DBH. *Environmental Monitoring and Assessment* 195(6):678. doi:10.1007/s10661-023-11366-8.
- **GUM2008** — JCGM 100:2008. Evaluation of measurement data — Guide to the expression of uncertainty in measurement (GUM).
- **Howie2024** — Howie N.A., De Stefano A. (2024). Measuring Tree Diameter Using LiDAR Equipped iPad: An Evaluation of ForestScanner and Arboreal Forest Applications. *Forest Science* 70(4):304–310. doi:10.1093/forsci/fxae017.
- **iPhoneMultiAlgo2024** — Tree parameter estimation with iPhone point cloud using multiple algorithms (2024). *International Journal of Remote Sensing*. doi:10.1080/01431161.2024.2409996.
- **Kasa1976** — Kåsa I. (1976). A circle fitting procedure and its error analysis. *IEEE Transactions on Instrumentation and Measurement* 25(1):8–14.
- **Larjavaara2013** — Larjavaara M., Muller-Landau H.C. (2013). Measuring tree height: a quantitative comparison of two common field methods in a moist tropical forest. *Methods in Ecology and Evolution* 4(9):793–801. doi:10.1111/2041-210X.12071.
- **Liang2013** — Liang X. et al. (2013). The influence of scan mode and circle fitting on tree stem detection, stem diameter and volume extraction from terrestrial laser scans. *ISPRS Journal of Photogrammetry and Remote Sensing* 81. doi:10.1016/j.isprsjprs.2012.10.001.
- **Liang2016** — Liang X. et al. (2016). Terrestrial laser scanning in forest inventories. *ISPRS Journal of Photogrammetry and Remote Sensing* 115:63–77. doi:10.1016/j.isprsjprs.2016.01.006.
- **Lin1989** — Lin L.I. (1989). A concordance correlation coefficient to evaluate reproducibility. *Biometrics* 45(1):255–268.
- **Mokros2021** — Mokroš M. et al. (2021). Novel low-cost mobile mapping systems for forest inventories as terrestrial laser scanning alternatives. *International Journal of Applied Earth Observation and Geoinformation* 104:102512. doi:10.1016/j.jag.2021.102512.
- **Pratt1987** — Pratt V. (1987). Direct least-squares fitting of algebraic surfaces. *ACM SIGGRAPH Computer Graphics* 21(4):145–152. doi:10.1145/37402.37420.
- **Pitkanen2021** — Pitkänen T.P. et al. (2021). Using auxiliary data to rationalize smartphone-based pre-harvest forest mensuration. *Forestry* 95(2):247–260. doi:10.1093/forestry/cpab039.
- **Sandim2023** — Sandim A. et al. (2023). New technologies for expedited forest inventory using smartphone applications. *Forests* 14(8):1553. doi:10.3390/f14081553.
- **Schuirmann1987** — Schuirmann D.J. (1987). A comparison of the two one-sided tests procedure and the power approach for assessing the equivalence of average bioavailability. *Journal of Pharmacokinetics and Biopharmaceutics* 15(6):657–680.
- **Sullivan2018** — Sullivan M.J.P. et al. (2018). Field methods for sampling tree height for tropical forest biomass estimation. *Methods in Ecology and Evolution* 9(5):1179–1189. doi:10.1111/2041-210X.12962.
- **Tatsumi2023** — Tatsumi S., Yamaguchi K., Furuya N. (2023). ForestScanner: A mobile application for measuring and mapping trees with LiDAR-equipped iPhone and iPad. *Methods in Ecology and Evolution* 14(7):1603–1609. doi:10.1111/2041-210X.13900.
- **Tatsumi_iPadBoreal2023** — Estimating DBH using iPad Pro LiDAR in boreal forests (2023). *Canadian Journal of Remote Sensing*. doi:10.1080/07038992.2023.2295470.
- **Taubin1991** — Taubin G. (1991). Estimation of planar curves, surfaces, and nonplanar space curves defined by implicit equations with applications to edge and range image segmentation. *IEEE Transactions on Pattern Analysis and Machine Intelligence* 13(11):1115–1138. doi:10.1109/34.103273.
- **Valentin2018** — Valentin J. et al. (2018). Depth from motion for smartphone AR. *ACM Transactions on Graphics* 37(6) (SIGGRAPH Asia). doi:10.1145/3272127.3275041.
- **Vastaranta2015** — Vastaranta M. et al. (2015). Evaluation of a smartphone app for forest sample plot measurements. *Forests* 6(4):1179–1194. doi:10.3390/f6041179.
- **Vinci2021_iphone** — Apple iPhone 12 Pro LiDAR evaluation (2021). *Scientific Reports* 11:22221. doi:10.1038/s41598-021-01763-9.
- **VIObench2022** — A Benchmark Comparison of Four Off-the-Shelf Proprietary Visual-Inertial Odometry Systems (2022). *Sensors* 22:9873. doi:10.3390/s22249873.
