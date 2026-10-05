# 日记小路最新需求（2026-10-05）

本文件记录已确定的产品规则和最新素材。本轮已在 diary-dev 实现，实际行为及补充选择见 `DIARY_IMPLEMENTATION_V6.md`，验证结果见根目录 `DIARY_DEV_NOTES.md`。

完整实现前规格、最新设置清单及导出建议见 `DIARY_REQUIREMENTS_V2.md`。该规格区分用户已确定需求与补充建议，优先于本文件早期的未完成菜单记录。

时间与排序最新规则见 `DIARY_TIME_ORDER_V3.md`：发生时间默认等于发送时间，可自定义；支持发生时间和发送时间两种排序，取消手动排序，并设计随当前排序变化的邻居位置预览。

收纳箱与保留策略以 `DIARY_INBOX_RETENTION_V5.md` 为准：草稿、撤回、单个随记删除以及取消整条小路均进入收纳箱，回收站严格只收整篇日记；默认永久保留，可由用户主动选择期限，不再默认7天。

## 首页与跨日

- 首页每天固定提供「今日小路」「今日结语」两个入口，同属当天的一份日记。
- 今日结语也可从今日小路右上角的日记按钮打开，两个入口编辑同一正文。
- 跨日后，当天的两个入口合并为一条历史日记，新一天再次提供两个空白入口。
- 自动提供的小路没有片段、结语有内容：历史条目直接打开文本日记。
- 自动提供的小路和结语都没有内容：不生成空白历史条目。
- 可补记过去日期，允许只有片段或只有正文。

## 旧文本日记与显式启用小路

- 手机已有的文本日记打开后仍直接显示文本。
- 在左上角设置中勾选「显示‘经历小路’」，切换到小路页面，原正文通过右上角日记按钮访问。
- 用户显式补上小路后，即使没有添加片段或删除全部片段，也不能自动取消小路。
- 必须将「用户已启用小路」与「片段是否为空」分别记录。
- 默认今日小路入口本身不等于用户已显式启用；不能因此让所有无片段的历史日记都显示空路。
- 完全无内容的日期仍不在首页生成空白历史条目。显式启用状态应独立持久化，方便再次补记时恢复。

## 背景与卡片

- 当前方向为夏日绿色树林：树叶更鲜绿，草地偏浅绿、灰绿，区分层次。
- 树叶与草地必须在形态上区分：树冠为阔叶，地面为细长草叶与自然草丛，不能把草地画成一堆树叶。
- 草地保留夏日 V2 的细长草叶及原有密度，用户已撤回 V3 的降低草地密度调整。仅降低树脚分根凸起、柔化阴影，减少强烈抓地感。
- 保留宽阔、自然蜿蜒的小路，以及路边石头、小草和树木。
- 景物保持浅色、柔和、水彩或轻漫画感觉，便于卡片阅读。
- 路面保留低对比暖光和少量宽而柔和的明暗变化，避免密集细碎光斑、强烈热点和尖锐树影。
- 不再固定一个弯口对应一个片段；卡片按时间从上到下、左右交错排列。
- 保留已认可效果图中的卡片尺寸，卡片高度随内容变化，使用舒适间距。
- 道路不为了片段增减而增加弯口；卡片无需严格贴合道路中心。
- 当前夏日素材：forest-summer-soft-v4.png，以 V2 为底稿仅柔化树根；效果图 diary-road-summer-layout-v1.png 仍使用旧版草地，仅作卡片排布参考。
- 内置背景形成四季一套，名称固定为「春径」「夏蹊」「秋陌」「冬途」，首套素材及提示词见 seasons/DESIGN_SEASONS_V1.md。
- 春径为花灌木、蜜蜂与小鸟；秋陌为金色稻田、伸向路沿的稻穗及稻草人；冬途采用雪后小路、积雪矮灌木与疏落树木，路面通过浅暖色与两旁积雪区分。
- 秋日版本保留作为设计历史，不覆盖原图。

## 设置菜单

- 今日小路、今日结语与历史日记的设置选项不同，应按页面和日期上下文提供适用选项。
- 今日小路包含独立的「更换背景」「选择排布方式」。背景按分类框展示，框内最多8张横向预览，右上角“>”进入独立分类页；第一类为本机导入，内置四季名称固定。
- 排布为左右交错（左侧开始）、全左、全右三项单选，圆圈选中填充蓝色。
- 历史某一天包含「显示‘经历小路’」复选框、背景、排布与导出。取消小路需确认，正文保留，完整小路移入收纳箱；默认永久保存，可主动设置保留期限。
- 导出为ZIP，按复选的小路和日记对应road及diary目录；首页导出先多选日期，再统一选内容和格式。格式与边界建议详见最新规格。
- 今日结语完整菜单尚未指定，其建议范围在最新规格中单独标明。

## 素材制作记录

生成方式：内置 image_gen。图片原始输出保留，项目内使用非覆盖的版本化副本。

### 夏日背景提示词

Use case: lighting-weather.
Asset type: tall scrolling mobile diary background.
Input image: Image 1 is the approved autumn background to edit.
Primary request: change the woodland foliage palette from autumn yellow/gold to summer green, preserving the approved composition.
Keep EXACTLY the image's tall portrait proportions, broad naturally winding beige dirt path geometry and width, every tree trunk, roadside rock, grass patch and moss, elevated oblique perspective, quiet road surface and broad diffuse low-contrast sunlight.
Color palette: tree leaves a fresh clear leafy green, subtly deeper and greener than the ground vegetation; roadside grass a lighter softer meadow green and grey-sage green; moss subdued sage green. Distinguish leaves and grass through hue and value without harsh contrast. Keep trees and stones in their natural pale grey-brown/grey colours. Recolour existing foliage, do not use a global green filter on the path or rocks. Recolour existing scattered leaf shapes naturally with subdued summer green/earth tones; avoid a carpet of golden autumn leaves. No yellow-gold autumn canopy.
Style: preserve the same airy pale watercolor/light manga-like illustration, soft edges and softened/translucent-looking distant vegetation. Foliage should be visibly green but restrained enough to sit behind readable diary cards, not dark dense emerald walls or neon lime.
Lighting: retain soft warm summer daylight and the existing gently shaded beige dirt surface, only a few broad subtle variations in brightness. NO dense light flecks, sharp twig shadows, brilliant hotspots, glowing circles or dramatic sunbeams.
Constraints: change the season colour only; no new objects, people, buildings, flowers, text, cards, UI, pins, connectors or borders; do not change path geometry, stone locations or crop.

### 夏日效果图提示词

Use case: compositing.
Asset type: diary mobile screen mockup.
Input images: Image 1 is the approved screen to edit. Image 2 is the summer woodland background to use behind the UI.
Replace ONLY Image 1's autumn woodland background with the summer-green woodland from Image 2. Preserve exactly Image 1's tall portrait dimensions, broad gently winding path, four alternating left-right-left-right cards and their dimensions and positions, card contents, warm ivory opaque surfaces, dark readable text, photo of a yellow leaf held in a hand (this is personal diary content, keep this photo unchanged), audio waveform, play icon, timestamps, rounded corners, soft shadows, Chinese header 今日小路 and date 10月5日, top navigation and book/settings icons, bottom composer 记下这一刻… with plus and send buttons. Do not recolour the cards or diary photo green.
Background tree leaves fresh summer green, visibly greener than lighter softer grass in meadow/sage greens, grey stones and natural pale tree trunks. Preserve Image 2's pale airy watercolor/manga-like scenery and subdued warm diffuse sunlight. Keep beige dirt road calm with broad soft brightness transitions, no dense sunlight flecks, no hard tree shadows, no hotspots. The cards remain independent chronological overlays, not pinned to turns; no new markers, connectors, bends or UI. No frame or annotations.
