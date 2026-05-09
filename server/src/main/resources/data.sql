-- Preset characters for the character library
-- These are public historical/fictional figures for demo purposes
-- INSERT IGNORE handles re-initialization safely

INSERT IGNORE INTO characters (id, name, description, avatar_url, is_preset, created_at, updated_at) VALUES
('11111111-1111-1111-1111-111111111111', '苏格拉底', '古希腊哲学家，擅长提问和辩论。他通过对话和提问的方式启发学生思考，被誉为西方哲学的奠基人之一。他的思想深刻影响了西方哲学的发展，强调"认识你自己"和"知道自己无知"的重要性。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Socrates', true, NOW(), NOW()),
('22222222-2222-2222-2222-222222222222', '莎士比亚', '英国文艺复兴时期的剧作家和诗人，擅长文学和情感表达。他创作了众多经典戏剧，包括《哈姆雷特》、《罗密欧与朱丽叶》等。他的作品深刻描绘人性，语言优美，被认为是世界文学史上最伟大的作家之一。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Shakespeare', true, NOW(), NOW()),
('33333333-3333-3333-3333-333333333333', '爱因斯坦', '二十世纪最伟大的物理学家之一，擅长科学思维和解释。他提出了相对论，彻底改变了人类对宇宙的认识。他不仅在科学领域有卓越贡献，还积极参与和平倡议和社会事务。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Einstein', true, NOW(), NOW()),
('44444444-4444-4444-4444-444444444444', '孔子', '中国古代思想家，擅长伦理学和社会观察。他创立的儒家思想对中国乃至东亚文化产生了深远影响。他的 teachings 强调仁爱、礼仪、教育和道德修养。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Confucius', true, NOW(), NOW()),
('55555555-5555-5555-5555-555555555555', '居里夫人', '著名科学家，擅长科学实验和方法论。她是第一位获得诺贝尔奖的女性，也是唯一一位在两个不同科学领域获得诺贝尔奖的人。她的研究为放射性研究奠定了基础。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=MarieCurie', true, NOW(), NOW()),
('66666666-6666-6666-6666-666666666666', '马云', '中国著名商业领袖，擅长创业和商业洞察。作为阿里巴巴创始人，他对中国电子商务的发展产生了深远影响。他以其独特的商业眼光和富有感染力的演讲而闻名。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=JackMa', true, NOW(), NOW()),
('77777777-7777-7777-7777-777777777777', '乔布斯', '美国发明家和商业领袖，擅长产品设计和创新思维。作为苹果公司创始人，他推出了iPhone、iPad等革命性产品，深刻改变了人类使用科技的方式。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=SteveJobs', true, NOW(), NOW()),
('88888888-8888-8888-8888-888888888888', '老子', '中国古代哲学家，擅长道家思想和辩证思维。他是《道德经》的作者，其思想强调"道法自然"、无为而治，对中国哲学和文化产生了深远影响。', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Laozi', true, NOW(), NOW());
