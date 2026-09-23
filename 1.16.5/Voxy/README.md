# Voxy 1.16.5 (Forge) — Версия с поддержкой шейдеров (Complementary Reimagined)

> [!IMPORTANT]
> **Текущий статус поддержки шейдеров:**
> В этой версии мода стабильно поддерживается и работает **ТОЛЬКО шейдерпак Complementary Reimagined**.  
> Другие шейдерпаки (включая **Photon**) **НЕ работают** корректно (дают визуальные артефакты, пересветку, рассинхрон буфера глубины). Разработка и поддержка шейдера Photon официально прекращена.

---

## 📁 Содержимое папки бекапа

- **`voxy-0.2.18-beta+mc1.16.5-forge.jar`** — готовый скомпилированный и проверенный .jar мода для Minecraft 1.16.5 Forge.
- **`src/`** — полный исходный код мода в стабильном состоянии (без дестабилизирующих экспериментов со смещением блоков и хаками глубины).
- **`libs/`** — скомпилированные зависимости и нативные библиотеки (Jabel, lz4, lmdb, и др.).
- **`build.gradle`**, **`gradle.properties`**, **`settings.gradle`** — конфигурация сборки Gradle под Java 8 bytecode.

---

## 🛠 Всё, что было сделано в проекте

### 1. Архитектурный перенос на 1.16.5 Forge и строгий Java 8
- **Bytecode Level 52**: Мод компилируется через Gradle 7.6.4 с плагином **Jabel** и флагом `--release 8`, что позволяет использовать современный синтаксис Java (pattern matching, records, switch expressions), генерируя 100% совместимый с Java 8 байткод.
- **Верификация**: Внедрены автоматические задачи `verifyJava8` и `verifyRuntimeRefs`, проверяющие все 351 класс на отсутствие вызовов JDK 9+ API (`List.of`, `Map.copyOf`, `Set.of`, новые методы `ByteBuffer` и т.д.).

### 2. Устранение чёрных чанков и проблем со светом
- **Потокобезопасная вокселизация (`SectionSnapshot`)**: Устранено состояние гонки при параллельном чтении палитр блоков воркерами Voxy во время динамического изменения размеров палитры Embeddium в основном потоке.
- **Синхронизация света (`MixinClientPacketListenerLight`)**: В 1.16.5 пакеты чанков и пакеты освещения приходят раздельно. Добавлен синхронный перехват `handleLightUpdatePacked`, фильтрация пустых массивов `NibbleArray` (`!data.isEmpty()`) и вычисление дефолтного скайлайта (`15` выше поверхности, `0` под землёй).
- **Безопасный доступ к соседям (`acquireIfExists`)**: Устранено создание пустых фантомных секций с нулевым светом при запросе границ чанков, что ранее приводило к чёрным стенам.

### 3. Совместимость с Embeddium 0.3.18
- Внедрён хук в `MixinChunkRenderManager` на метод `addChunkToRenderLists` с проверкой `chunk.getFacesWithData() != 0`, что исключило рендер незавершённых мешей и устранило «дыры» вокруг игрока.

### 4. Интеграция с Oculus 1.4.8 (Iris 1.6.4) и поддержка Complementary Reimagined
- **Синхронизация физики и материалов воды (ID `32000`)**:
  - В [`IrisVoxyRenderPipeline.java`](src/main/java/me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java) реализован метод `setupExtraModelBakeryData`, передающий соответствие ID блоков из шейдерпака Oculus в бейкер моделей Voxy.
  - В [`ModelFactory.java`](src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java) блокам воды на GPU назначается кастомный ID `32000`. Благодаря этому шейдер `voxy_translucent.glsl` применяет к воде LoD точно такие же волны, зеркальные отражения неба, прозрачность и физику, как на ванильной воде вблизи игрока.
- **Инициализация FBO глубины для Linux/Mesa драйверов**:
  - В [`DepthFramebuffer.java`](src/main/java/me/cortex/voxy/client/core/rendering/util/DepthFramebuffer.java) текстурам глубины заданы параметры `GL_NEAREST` и `GL_CLAMP_TO_EDGE`. Это устранило проблему «неполных» фреймбуферов (FBO incomplete), из-за которой Mesa драйвер возвращал глубину `0.0`.
- **Гарантированная привязка сэмплеров глубины**:
  - В [`OculusPipelineBridge.java`](src/main/java/me/cortex/voxy/client/core/util/OculusPipelineBridge.java) обеспечена привязка `vxDepthTexOpaque` и `vxDepthTexTrans`, предотвращающая чтение сэмплера из нулевого текстурного юнита.
- **Горячая смена шейдеров (Hot-Swap) и очистка GL-состояния**:
  - В [`VoxyRenderSystem.java`](src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java) метод `checkPipelineSwap()` отслеживает смену шейдерпака без перезапуска игры. После отрисовки Voxy очищаются все 32 текстурных юнита с возвратом на `GL_TEXTURE0`.
- **Смещение маски чанков против мерцания (`BOUND_DEPTH_BIAS = 0.0005f`)**:
  - В [`BoundRenderer.java`](src/main/java/me/cortex/voxy/client/core/rendering/bounding/BoundRenderer.java) сохранено смещение `0.0005f`, предотвращающее z-fighting между геометрией ванильных чанков и дальними LoD.

---

## ⚠️ Неудачные эксперименты, которые были отменены

При попытке устранить тонкую тёмную полоску (стык) на воде были протестированы следующие решения, показавшие негативный результат и откатанные назад:

1. **`BOUND_DEPTH_BIAS = 0.0`**:
   - *Результат:* Вызвало сильное мерцание блоков на дальней границе («блоки моргают на пустоту»), так как числа с плавающей точкой в depth buffer не имели запаса точности. Возвращено `0.0005f`.
2. **`MixinFluidRenderer` (Подавление вертикальных стенок воды Embeddium)**:
   - *Результат:* Вызвало чёрные зубчатые артефакты на границе песка и воды. Миксин полностью удалён.
3. **`skipShaderDepthHackFix = true` и инъекция `DoFog` в `IrisShaderPatch`**:
   - *Результат:* Вызвало диагональные полосы и пятна теней на ландшафте, так как композитный проход Complementary ожидал маскированную глубину. Изменения полностью удалены.

---

## 🔨 Инструкция по повторной сборке из исходников

Для сборки требуется:
- Java 8 (для рантайма/компилятора)
- Java 17 Temurin (для запуска Gradle демона)

Команда сборки:
```bash
JAVA_HOME=/path/to/openjdk-8 \
./gradlew \
  --offline --no-daemon --console=plain \
  --gradle-user-home .buildenv/gradle-home-jdk8 \
  -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-17-temurin-jdk \
  -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-temurin-jdk \
  build
```
Собранный jar будет расположен в `build/libs/voxy-0.2.18-beta.jar`.
