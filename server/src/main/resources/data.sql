-- Preset characters for the character library
-- These are public historical figures for demo purposes
-- INSERT IGNORE handles re-initialization safely
-- Order matters: findRecommended() returns rows in the order stored here,
-- which is the order used to lay out the "Recommended Characters" 3×6 grid.

INSERT IGNORE INTO characters (id, name, description, avatar_url, is_preset, created_at, updated_at) VALUES
('00000000-0000-0000-0000-000000000001', '孔子',     '中国古代思想家，儒家学派创始人，主张仁爱、礼治与有教无类，被尊为"万世师表"，对东亚文化圈影响逾两千年。',                       'https://api.dicebear.com/7.x/avataaars/svg?seed=Confucius',  true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000002', '苏格拉底', '古希腊哲学家，开创西方思辨哲学传统，主张"认识你自己"与通过对话（产婆术）逼近真理，对柏拉图、亚里士多德及后世哲学影响深远。',   'https://api.dicebear.com/7.x/avataaars/svg?seed=Socrates',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000003', '老子',     '中国古代哲学家，道家学派创始人，《道德经》作者，倡导"道法自然"与无为而治，深刻塑造中国哲学与东方思维方式。',                       'https://api.dicebear.com/7.x/avataaars/svg?seed=Laozi',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000004', '释迦牟尼', '古印度思想家，佛教创立者，主张四圣谛、八正道与众生平等，其教义传播至东亚、东南亚及全球，影响数十亿人。',                     'https://api.dicebear.com/7.x/avataaars/svg?seed=Buddha',     true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000005', '耶稣',     '公元1世纪犹太传道者，基督教创立的核心人物，宣扬爱、宽恕与救赎，基督教后成为全球最大宗教之一，影响西方文明两千余年。',         'https://api.dicebear.com/7.x/avataaars/svg?seed=Jesus',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000006', '穆罕默德', '阿拉伯先知，伊斯兰教创立者，传达《古兰经》启示，统一阿拉伯半岛，伊斯兰教后成为全球第二大宗教，影响数十亿信徒。',               'https://api.dicebear.com/7.x/avataaars/svg?seed=Muhammad',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000007', '伽利略',   '意大利科学家，现代实验科学奠基人之一，改进望远镜并支持日心说，为牛顿力学开辟道路，被誉为"近代科学之父"。',                       'https://api.dicebear.com/7.x/avataaars/svg?seed=Galileo',    true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000008', '牛顿',     '英国物理学家与数学家，经典力学奠基者，《自然哲学的数学原理》作者，提出万有引力与三大运动定律，塑造现代科学世界观。',           'https://api.dicebear.com/7.x/avataaars/svg?seed=Newton',     true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000009', '达尔文',   '英国博物学家，进化论奠基人，《物种起源》作者，提出自然选择学说，重塑人类对自身起源与生命多样性的理解。',                     'https://api.dicebear.com/7.x/avataaars/svg?seed=Darwin',     true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000010', '爱因斯坦', '德裔美国理论物理学家，相对论提出者，质能方程 E=mc² 作者，1921 年诺贝尔物理学奖得主，深刻改变人类对时空与宇宙的认知。',         'https://api.dicebear.com/7.x/avataaars/svg?seed=Einstein',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000011', '居里夫人', '波兰裔法国物理学家与化学家，放射性研究先驱，1903 年与 1911 年两度诺贝尔奖得主，唯一在两个不同科学领域获奖的人。',           'https://api.dicebear.com/7.x/avataaars/svg?seed=MarieCurie', true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000012', '特斯拉',   '塞尔维亚裔美国发明家，交流电、感应电机与特斯拉线圈的发明者，为现代电力传输与无线电技术奠定基础。',                         'https://api.dicebear.com/7.x/avataaars/svg?seed=Tesla',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000013', '莎士比亚', '英国文艺复兴时期剧作家与诗人，《哈姆雷特》《李尔王》等传世，对英语文学与世界戏剧影响深远，被尊为"人类文学奥林匹斯山上的宙斯"。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Shakespeare', true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000014', '柏拉图',   '古希腊哲学家，苏格拉底的学生、亚里士多德的老师，创办学园（Academy），理念论与对话体哲学奠基人，西方哲学传统最重要的源头之一。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Plato',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000015', '达·芬奇', '意大利文艺复兴时期博学家，画家（《蒙娜丽莎》《最后的晚餐》）、发明家、解剖学家与工程师，艺术与科学通才的象征。',             'https://api.dicebear.com/7.x/avataaars/svg?seed=DaVinci',    true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000016', '成吉思汗', '蒙古帝国缔造者，13 世纪初统一蒙古各部，发动横跨欧亚的大规模征服，深刻重塑中世纪地缘政治与东西方交流格局。',                 'https://api.dicebear.com/7.x/avataaars/svg?seed=Genghis',    true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000017', '拿破仑',   '法国军事家与政治家，19 世纪初建立法兰西第一帝国，重组欧洲政治版图与法律体系（《拿破仑法典》），影响现代国家治理。',           'https://api.dicebear.com/7.x/avataaars/svg?seed=Napoleon',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000018', '毛泽东',   '中国革命家、政治家与思想家，中国共产党与中华人民共和国的主要缔造者之一，20 世纪最具影响力的政治人物之一，深刻塑造现代中国。',     'https://api.dicebear.com/7.x/avataaars/svg?seed=Mao',        true, NOW(), NOW()),
-- 扩容第二批 18 人（19-36）。prompt 字段未列 → 由 CharacterService.generatePromptFromWeb 在用户首次点选时联网生成，
-- 与 DataLoader seed 行为保持一致。avatarUrl 同样走 DiceBear personas（与前端 RoomListView.vue 兜底格式一致）。
('00000000-0000-0000-0000-000000000019', '亚里士多德', '古希腊哲学家，柏拉图的学生、亚历山大大帝的老师，形式逻辑与生物学奠基人，著有《尼各马可伦理学》《政治学》等。',                            'https://api.dicebear.com/7.x/personas/svg?seed=Aristotle',  true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000020', '马克思',     '德国思想家、哲学家与经济学家，《资本论》与《共产党宣言》作者之一，科学社会主义奠基人，深刻塑造 19-20 世纪世界政治与思想版图。',       'https://api.dicebear.com/7.x/personas/svg?seed=Marx',        true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000021', '列宁',       '俄国革命家与政治家，布尔什维克领袖，十月革命领导者，苏维埃政权缔造者，马克思主义的继承者与发展者之一。',                              'https://api.dicebear.com/7.x/personas/svg?seed=Lenin',       true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000022', '卢梭',       '启蒙时代法国思想家，《社会契约论》《爱弥儿》作者，提出"人生而自由"、"主权在民"等理念，深刻影响法国大革命与现代民主理论。',           'https://api.dicebear.com/7.x/personas/svg?seed=Rousseau',    true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000023', '伏尔泰',     '法国启蒙思想家、作家与哲学家，捍卫公民自由、宗教宽容与理性主义，代表作《老实人》《哲学通信》，被誉为「启蒙运动旗手」。',              'https://api.dicebear.com/7.x/personas/svg?seed=Voltaire',    true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000024', '康德',       '德国古典哲学家，《纯粹理性批判》《实践理性批判》作者，先验哲学奠基人，提出「头顶星空与心中道德律」，深刻塑造现代哲学。',              'https://api.dicebear.com/7.x/personas/svg?seed=Kant',        true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000025', '黑格尔',     '德国古典哲学家，唯心主义辩证法集大成者，《精神现象学》《逻辑学》作者，马克思哲学的重要思想来源。',                                       'https://api.dicebear.com/7.x/personas/svg?seed=Hegel',       true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000026', '尼采',       '德国哲学家、诗人与文化批评家，《查拉图斯特拉如是说》《善恶的彼岸》作者，宣告「上帝已死」，对存在主义与后现代思想影响深远。',           'https://api.dicebear.com/7.x/personas/svg?seed=Nietzsche',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000027', '弗洛伊德',   '奥地利心理学家，精神分析学创始人，《梦的解析》作者，提出本我/自我/超我人格结构，开创现代心理治疗体系。',                             'https://api.dicebear.com/7.x/personas/svg?seed=Freud',       true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000028', '伽罗瓦',     '法国数学家，伽罗瓦理论创立者，群论奠基人之一，20 岁早逝于决斗，其抽象代数思想深刻塑造现代数学。',                                       'https://api.dicebear.com/7.x/personas/svg?seed=Galois',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000029', '高斯',       '德国数学家、物理学家与天文学家，「数学王子」，在数论、统计、测地学、电学等领域均有奠基性贡献。',                                       'https://api.dicebear.com/7.x/personas/svg?seed=Gauss',       true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000030', '麦克斯韦',   '英国理论物理学家，经典电磁理论奠基人，麦克斯韦方程组统一了电、磁、光，预言电磁波存在，被誉为「仅次于牛顿、爱因斯坦的物理学家」。',  'https://api.dicebear.com/7.x/personas/svg?seed=Maxwell',     true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000031', '玻尔',       '丹麦物理学家，原子结构量子化模型提出者，哥本哈根诠释主要人物，1922 年诺贝尔物理学奖得主，量子力学奠基人之一。',                    'https://api.dicebear.com/7.x/personas/svg?seed=Bohr',        true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000032', '海森堡',     '德国理论物理学家，量子力学矩阵力学创立者之一，不确定性原理提出者，1932 年诺贝尔物理学奖得主。',                                      'https://api.dicebear.com/7.x/personas/svg?seed=Heisenberg',  true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000033', '巴赫',       '德国巴洛克时期作曲家，《平均律钢琴曲集》《马太受难曲》《勃兰登堡协奏曲》作者，被誉为「西方音乐之父」。',                            'https://api.dicebear.com/7.x/personas/svg?seed=Bach',        true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000034', '莫扎特',     '奥地利古典主义作曲家，5 岁作曲、8 岁首演交响曲，代表作《费加罗的婚礼》《唐璜》《安魂曲》，短暂一生留下 600+ 部作品。',            'https://api.dicebear.com/7.x/personas/svg?seed=Mozart',      true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000035', '贝多芬',     '德国作曲家，维也纳古典与浪漫主义过渡的桥梁人物，代表作《英雄交响曲》《命运交响曲》《第九交响曲》（含《欢乐颂》），被誉为「乐圣」。', 'https://api.dicebear.com/7.x/personas/svg?seed=Beethoven',   true, NOW(), NOW()),
('00000000-0000-0000-0000-000000000036', '梵高',       '荷兰后印象派画家，《星夜》《向日葵》《自画像》作者，生前默默无闻身后成为现代艺术最具影响力的人物之一。',                       'https://api.dicebear.com/7.x/personas/svg?seed=VanGogh',     true, NOW(), NOW());