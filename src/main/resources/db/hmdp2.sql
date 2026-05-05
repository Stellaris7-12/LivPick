-- 允许零日期，避免 timestamp 默认值 '0000-00-00 00:00:00' 报错
SET SESSION sql_mode = 'ALLOW_INVALID_DATES';
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_blog
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog`;
CREATE TABLE `tb_blog`  (
                            `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                            `shop_id` bigint(20) NOT NULL COMMENT '商户id',
                            `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
                            `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
                            `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '探店的照片，最多9张，多张以\",\"隔开',
                            `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '探店的文字描述',
                            `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
                            `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
                            `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 18 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog
-- ----------------------------
INSERT INTO `tb_blog` VALUES (4, 4, 2, '无尽浪漫的夜晚丨在万花丛中摇晃着红酒杯🍷品战斧牛排🥩', '/imgs/blogs/7/14/4771fefb-1a87-4252-816c-9f7ec41ffa4a.jpg,/imgs/blogs/4/10/2f07e3c9-ddce-482d-9ea7-c21450f8d7cd.jpg,/imgs/blogs/2/6/b0756279-65da-4f2d-b62a-33f74b06454a.jpg,/imgs/blogs/10/7/7e97f47d-eb49-4dc9-a583-95faa7aed287.jpg,/imgs/blogs/1/2/4a7b496b-2a08-4af7-aa95-df2c3bd0ef97.jpg,/imgs/blogs/14/3/52b290eb-8b5d-403b-8373-ba0bb856d18e.jpg', '生活就是一半烟火·一半诗意\n手执烟火谋生活·心怀诗意以谋爱·\n当然 男朋友给不了的浪漫要学会自己给🍒\n无法重来的一生·尽量快乐.\n\n🏰「小筑里·神秘浪漫花园餐厅」🏰\n\n💯这是一家最最最美花园的西餐厅·到处都是花餐桌上是花前台是花  美好无处不在\n品一口葡萄酒，维亚红酒马瑟兰·微醺上头工作的疲惫消失无际·生如此多娇🍃\n\n📍地址:延安路200号(家乐福面)\n\n🚌交通:地铁①号线定安路B口出右转过下通道右转就到啦～\n\n--------------🥩菜品详情🥩---------------\n\n「战斧牛排」\n超大一块战斧牛排经过火焰的炙烤发出阵阵香，外焦里嫩让人垂涎欲滴，切开牛排的那一刻，牛排的汁水顺势流了出来，分熟的牛排肉质软，简直细嫩到犯规，一刻都等不了要放入嘴里咀嚼～\n\n「奶油培根意面」\n太太太好吃了💯\n我真的无法形容它的美妙，意面混合奶油香菇的香味真的太太太香了，我真的舔盘了，一丁点美味都不想浪费‼️\n\n「香菜汁烤鲈鱼」\n这个酱是辣的 真的绝好吃‼️\n鲈鱼本身就很嫩没什么刺，烤过之后外皮酥酥的，鱼肉蘸上酱料根本停不下来啊啊啊啊\n能吃辣椒的小伙伴一定要尝尝\n\n 非常可 好吃子🍽\n\n--------------🍃个人感受🍃---------------\n\n【👩🏻‍🍳服务】\n小姐姐特别耐心的给我们介绍彩票 推荐特色菜品，拍照需要帮忙也是尽心尽力配合，太爱他们了\n\n【🍃环境】\n比较有格调的西餐厅 整个餐厅的布局可称得上的万花丛生 有种在人间仙境的感觉🌸\n集美食美酒与鲜花为一体的风格店铺 令人向往\n烟火皆是生活 人间皆是浪漫', 13, 104, '2021-12-28 19:50:01', '2022-01-06 20:30:03');
INSERT INTO `tb_blog` VALUES (5, 1, 2, '人均30💰杭州这家港式茶餐厅我疯狂打call‼️', '/imgs/blogs/4/7/863cc302-d150-420d-a596-b16e9232a1a6.jpg,/imgs/blogs/11/12/8b37d208-9414-4e78-b065-9199647bb3e3.jpg,/imgs/blogs/4/1/fa74a6d6-3026-4cb7-b0b6-35abb1e52d11.jpg,/imgs/blogs/9/12/ac2ce2fb-0605-4f14-82cc-c962b8c86688.jpg,/imgs/blogs/4/0/26a7cd7e-6320-432c-a0b4-1b7418f45ec7.jpg,/imgs/blogs/15/9/cea51d9b-ac15-49f6-b9f1-9cf81e9b9c85.jpg', '又吃到一家好吃的茶餐厅🍴环境是怀旧tvb港风📺边吃边拍照片📷几十种菜品均价都在20+💰可以是很平价了！\n·\n店名：九记冰厅(远洋店)\n地址：杭州市丽水路远洋乐堤港负一楼（溜冰场旁边）\n·\n✔️黯然销魂饭（38💰）\n这碗饭我吹爆！米饭上盖满了甜甜的叉烧 还有两颗溏心蛋🍳每一粒米饭都裹着浓郁的酱汁 光盘了\n·\n✔️铜锣湾漏奶华（28💰）\n黄油吐司烤的脆脆的 上面洒满了可可粉🍫一刀切开 奶盖流心像瀑布一样流出来  满足\n·\n✔️神仙一口西多士士（16💰）\n简简单单却超级好吃！西多士烤的很脆 黄油味浓郁 面包体超级柔软 上面淋了炼乳\n·\n✔️怀旧五柳炸蛋饭（28💰）\n四个鸡蛋炸成蓬松的炸蛋！也太好吃了吧！还有大块鸡排 上淋了酸甜的酱汁 太合我胃口了！！\n·\n✔️烧味双拼例牌（66💰）\n选了烧鹅➕叉烧 他家烧腊品质真的惊艳到我！据说是每日广州发货 到店现烧现卖的黑棕鹅 每口都是正宗的味道！肉质很嫩 皮超级超级酥脆！一口爆油！叉烧肉也一点都不柴 甜甜的很入味 搭配梅子酱很解腻 ！\n·\n✔️红烧脆皮乳鸽（18.8💰）\n乳鸽很大只 这个价格也太划算了吧， 肉质很有嚼劲 脆皮很酥 越吃越香～\n·\n✔️大满足小吃拼盘（25💰）\n翅尖➕咖喱鱼蛋➕蝴蝶虾➕盐酥鸡\nzui喜欢里面的咖喱鱼！咖喱酱香甜浓郁！鱼蛋很q弹～\n·\n✔️港式熊仔丝袜奶茶（19💰）\n小熊🐻造型的奶茶冰也太可爱了！颜值担当 很地道的丝袜奶茶 茶味特别浓郁～\n·', 2, 0, '2021-12-28 20:57:49', '2022-01-06 20:30:22');
INSERT INTO `tb_blog` VALUES (6, 10, 1, '杭州周末好去处｜💰50就可以骑马啦🐎', '/imgs/blogs/blog1.jpg', '杭州周末好去处｜💰50就可以骑马啦🐎', 11, 0, '2022-01-11 16:05:47', '2022-01-11 16:05:47');
INSERT INTO `tb_blog` VALUES (7, 10, 1, '杭州周末好去处｜💰50就可以骑马啦🐎', '/imgs/blogs/blog1.jpg', '杭州周末好去处｜💰50就可以骑马啦🐎', 11, 0, '2022-01-11 16:05:47', '2022-01-11 16:05:47');
INSERT INTO `tb_blog` VALUES (8, 2, 6, '这家铜锅涮肉让我吃到了老北京的味道！', '/imgs/blogs/blog1.jpg', '羊肉特别嫩，麻酱很正宗，糖蒜免费续！推荐手切鲜羊肉和牛百叶。', 27, 12, '2022-01-20 12:30:00', '2022-01-20 12:30:00');
INSERT INTO `tb_blog` VALUES (9, 3, 7, '新白鹿yyds！性价比之王', '/imgs/blogs/blog1.jpg', '蛋黄鸡翅必点，糖醋里脊只要9块钱，两个人80块吃到撑。', 56, 34, '2022-01-21 19:15:00', '2022-01-21 19:15:00');
INSERT INTO `tb_blog` VALUES (10, 5, 8, '海底捞的服务永远让人感动', '/imgs/blogs/blog1.jpg', '等位做了美甲，送了小零食，番茄牛肉粒汤喝了三碗。', 89, 45, '2022-01-22 21:00:00', '2022-01-22 21:00:00');
INSERT INTO `tb_blog` VALUES (11, 7, 9, '炉鱼的烤鱼外焦里嫩，超下饭', '/imgs/blogs/blog1.jpg', '推荐蒜香味，加一份宽粉和腐竹，绝配！', 34, 18, '2022-01-23 18:30:00', '2022-01-23 18:30:00');
INSERT INTO `tb_blog` VALUES (12, 8, 10, '浅草屋性价比太高了，寿司拼盘只要48', '/imgs/blogs/blog1.jpg', '鳗鱼饭很香，三文鱼新鲜，服务也好。', 41, 22, '2022-01-24 13:20:00', '2022-01-24 13:20:00');
INSERT INTO `tb_blog` VALUES (13, 10, 11, '开乐迪音效不错，曲库很全', '/imgs/blogs/blog1.jpg', '下午场很便宜，50块钱唱一下午，还有免费小吃。', 22, 9, '2022-01-25 15:45:00', '2022-01-25 15:45:00');
INSERT INTO `tb_blog` VALUES (14, 11, 12, 'INLOVE包厢很大，适合聚会', '/imgs/blogs/blog1.jpg', '我们十个人订了总统套，音响效果震撼，下次还来。', 38, 15, '2022-01-26 20:10:00', '2022-01-26 20:10:00');
INSERT INTO `tb_blog` VALUES (15, 12, 13, '乐堤港的魅KTV，环境超棒', '/imgs/blogs/blog1.jpg', '装修很时尚，服务热情，酒水价格合理。', 19, 7, '2022-01-27 22:30:00', '2022-01-27 22:30:00');
INSERT INTO `tb_blog` VALUES (16, 13, 14, '北城天地的讴K拉，学生党福音', '/imgs/blogs/blog1.jpg', '团购很划算，下午场人均30，适合学生聚会。', 31, 11, '2022-01-28 14:00:00', '2022-01-28 14:00:00');
INSERT INTO `tb_blog` VALUES (17, 14, 15, '星聚会的环境确实好，适合商务', '/imgs/blogs/blog1.jpg', '每个包厢都有独立卫生间，还有投影仪，商务聚会首选。', 47, 23, '2022-01-29 19:40:00', '2022-01-29 19:40:00');

-- ----------------------------
-- Table structure for tb_blog_comments
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog_comments`;
CREATE TABLE `tb_blog_comments`  (
                                     `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                                     `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
                                     `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '探店id',
                                     `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
                                     `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
                                     `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
                                     `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
                                     `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
                                     `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                     PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog_comments
-- ----------------------------
INSERT INTO `tb_blog_comments` VALUES (1, 1, 8, 0, 0, '这家店我也去过，羊肉确实不错！', 12, 0, '2022-01-20 13:00:00', '2022-01-20 13:00:00');
INSERT INTO `tb_blog_comments` VALUES (2, 2, 8, 0, 0, '麻酱是灵魂，哈哈哈', 8, 0, '2022-01-20 14:22:00', '2022-01-20 14:22:00');
INSERT INTO `tb_blog_comments` VALUES (3, 6, 8, 1, 1, '对，他们家的糖蒜我也爱', 3, 0, '2022-01-20 15:10:00', '2022-01-20 15:10:00');
INSERT INTO `tb_blog_comments` VALUES (4, 7, 9, 0, 0, '蛋黄鸡翅每次去必点！还有冰淇淋烤布蕾', 25, 0, '2022-01-21 20:05:00', '2022-01-21 20:05:00');
INSERT INTO `tb_blog_comments` VALUES (5, 8, 9, 0, 0, '上次排队排了两小时，但值得', 9, 0, '2022-01-22 09:30:00', '2022-01-22 09:30:00');
INSERT INTO `tb_blog_comments` VALUES (6, 9, 9, 4, 4, '冰淇淋烤布蕾我也超爱！', 2, 0, '2022-01-22 11:45:00', '2022-01-22 11:45:00');
INSERT INTO `tb_blog_comments` VALUES (7, 10, 10, 0, 0, '海底捞的服务真的无可挑剔', 30, 0, '2022-01-22 22:15:00', '2022-01-22 22:15:00');
INSERT INTO `tb_blog_comments` VALUES (8, 11, 10, 0, 0, '番茄锅底yyds！', 18, 0, '2022-01-23 10:30:00', '2022-01-23 10:30:00');
INSERT INTO `tb_blog_comments` VALUES (9, 12, 10, 7, 7, '每次必点番茄锅，还要加牛肉粒', 5, 0, '2022-01-23 14:20:00', '2022-01-23 14:20:00');
INSERT INTO `tb_blog_comments` VALUES (10, 13, 11, 0, 0, '炉鱼的宽粉吸满汤汁太爽了', 14, 0, '2022-01-23 19:00:00', '2022-01-23 19:00:00');
INSERT INTO `tb_blog_comments` VALUES (11, 14, 12, 0, 0, '浅草屋的三文鱼丼也不错', 7, 0, '2022-01-24 14:50:00', '2022-01-24 14:50:00');
INSERT INTO `tb_blog_comments` VALUES (12, 15, 13, 0, 0, '开乐迪的会员日很划算', 5, 0, '2022-01-25 16:30:00', '2022-01-25 16:30:00');
INSERT INTO `tb_blog_comments` VALUES (13, 1, 14, 0, 0, 'INLOVE的音响是JBL的吧，效果棒', 11, 0, '2022-01-26 21:45:00', '2022-01-26 21:45:00');
INSERT INTO `tb_blog_comments` VALUES (14, 2, 15, 0, 0, '魅KTV的酒水价格比外面贵一点', 2, 0, '2022-01-27 23:20:00', '2022-01-27 23:20:00');
INSERT INTO `tb_blog_comments` VALUES (15, 3, 17, 0, 0, '星聚会的环境确实好，适合商务', 9, 0, '2022-01-29 20:15:00', '2022-01-29 20:15:00');

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
                              `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
                              `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
                              `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
                              `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------
INSERT INTO `tb_follow` VALUES (1, 1, 2, '2022-01-05 10:00:00');
INSERT INTO `tb_follow` VALUES (2, 1, 6, '2022-01-10 11:00:00');
INSERT INTO `tb_follow` VALUES (3, 2, 1, '2022-01-05 12:00:00');
INSERT INTO `tb_follow` VALUES (4, 2, 7, '2022-01-11 13:00:00');
INSERT INTO `tb_follow` VALUES (5, 4, 1, '2022-01-07 14:00:00');
INSERT INTO `tb_follow` VALUES (6, 5, 2, '2022-01-08 15:00:00');
INSERT INTO `tb_follow` VALUES (7, 6, 1, '2022-01-10 16:00:00');
INSERT INTO `tb_follow` VALUES (8, 6, 2, '2022-01-11 17:00:00');
INSERT INTO `tb_follow` VALUES (9, 7, 8, '2022-01-12 18:00:00');
INSERT INTO `tb_follow` VALUES (10, 8, 7, '2022-01-12 19:00:00');
INSERT INTO `tb_follow` VALUES (11, 8, 15, '2022-01-15 20:00:00');
INSERT INTO `tb_follow` VALUES (12, 9, 6, '2022-01-13 21:00:00');
INSERT INTO `tb_follow` VALUES (13, 10, 1, '2022-01-14 22:00:00');
INSERT INTO `tb_follow` VALUES (14, 10, 11, '2022-01-15 23:00:00');
INSERT INTO `tb_follow` VALUES (15, 11, 10, '2022-01-15 09:00:00');
INSERT INTO `tb_follow` VALUES (16, 12, 13, '2022-01-16 10:00:00');
INSERT INTO `tb_follow` VALUES (17, 13, 12, '2022-01-17 11:00:00');
INSERT INTO `tb_follow` VALUES (18, 14, 15, '2022-01-18 12:00:00');
INSERT INTO `tb_follow` VALUES (19, 15, 1, '2022-01-19 13:00:00');
INSERT INTO `tb_follow` VALUES (20, 15, 8, '2022-01-20 14:00:00');

-- ----------------------------
-- Table structure for tb_seckill_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_seckill_voucher`;
CREATE TABLE `tb_seckill_voucher`  (
                                       `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
                                       `stock` int(8) NOT NULL COMMENT '库存',
                                       `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '生效时间',
                                       `end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '失效时间',
                                       `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀优惠券表，与优惠券是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_seckill_voucher
-- ----------------------------
INSERT INTO `tb_seckill_voucher` VALUES (7, 100, '2022-02-06 00:00:00', '2022-02-10 10:00:00', '2022-02-10 22:00:00', '2022-02-06 00:00:00');
INSERT INTO `tb_seckill_voucher` VALUES (8, 50, '2022-02-07 00:00:00', '2022-02-11 17:00:00', '2022-02-11 21:00:00', '2022-02-07 00:00:00');
INSERT INTO `tb_seckill_voucher` VALUES (9, 80, '2022-02-08 00:00:00', '2022-02-12 11:00:00', '2022-02-12 20:30:00', '2022-02-08 00:00:00');
INSERT INTO `tb_seckill_voucher` VALUES (10, 30, '2022-02-09 00:00:00', '2022-02-13 13:00:00', '2022-02-13 18:00:00', '2022-02-09 00:00:00');
INSERT INTO `tb_seckill_voucher` VALUES (11, 200, '2022-02-10 00:00:00', '2022-02-14 10:30:00', '2022-02-14 14:00:00', '2022-02-10 00:00:00');

-- ----------------------------
-- Table structure for tb_shop
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop`;
CREATE TABLE `tb_shop`  (
                            `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                            `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺名称',
                            `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '商铺类型的id',
                            `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺图片，多个图片以\',\'隔开',
                            `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商圈，例如陆家嘴',
                            `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
                            `x` double UNSIGNED NOT NULL COMMENT '经度',
                            `y` double UNSIGNED NOT NULL COMMENT '维度',
                            `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
                            `sold` int(10) UNSIGNED NOT NULL COMMENT '销量',
                            `comments` int(10) UNSIGNED NOT NULL COMMENT '评论数量',
                            `score` int(2) UNSIGNED NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
                            `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
                            `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            PRIMARY KEY (`id`) USING BTREE,
                            INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop
-- ----------------------------
INSERT INTO `tb_shop` VALUES (1, '103茶餐厅', 1, 'https://qcloud.dpfile.com/pc/jiclIsCKmOI2arxKN1Uf0Hx3PucIJH8q0QSz-Z8llzcN56-_QiKuOvyio1OOxsRtFoXqu0G3iT2T27qat3WhLVEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vfCF2ubeXzk49OsGrXt_KYDCngOyCwZK-s3fqawWswzk.jpg,https://qcloud.dpfile.com/pc/IOf6VX3qaBgFXFVgp75w-KKJmWZjFc8GXDU8g9bQC6YGCpAmG00QbfT4vCCBj7njuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '金华路锦昌文华苑29号', 120.149192, 30.316078, 80, 4215, 3035, 37, '10:00-22:00', '2021-12-22 18:10:39', '2022-01-13 17:32:19');
INSERT INTO `tb_shop` VALUES (2, '蔡馬洪涛烤肉·老北京铜锅涮羊肉', 1, 'https://p0.meituan.net/bbia/c1870d570e73accbc9fee90b48faca41195272.jpg,http://p0.meituan.net/mogu/397e40c28fc87715b3d5435710a9f88d706914.jpg,https://qcloud.dpfile.com/pc/MZTdRDqCZdbPDUO0Hk6lZENRKzpKRF7kavrkEI99OxqBZTzPfIxa5E33gBfGouhFuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '上塘路1035号（中国工商银行旁）', 120.151505, 30.333422, 85, 2160, 1460, 46, '11:30-03:00', '2021-12-22 19:00:13', '2022-01-11 16:12:26');
INSERT INTO `tb_shop` VALUES (3, '新白鹿餐厅(运河上街店)', 1, 'https://p0.meituan.net/biztone/694233_1619500156517.jpeg,https://img.meituan.net/msmerchant/876ca8983f7395556eda9ceb064e6bc51840883.png,https://img.meituan.net/msmerchant/86a76ed53c28eff709a36099aefe28b51554088.png', '运河上街', '台州路2号运河上街购物中心F5', 120.151954, 30.32497, 61, 12035, 8045, 47, '10:30-21:00', '2021-12-22 19:10:05', '2022-01-11 16:12:42');
INSERT INTO `tb_shop` VALUES (4, 'Mamala(杭州远洋乐堤港店)', 1, 'https://img.meituan.net/msmerchant/232f8fdf09050838bd33fb24e79f30f9606056.jpg,https://qcloud.dpfile.com/pc/rDe48Xe15nQOHCcEEkmKUp5wEKWbimt-HDeqYRWsYJseXNncvMiXbuED7x1tXqN4uzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '丽水路66号远洋乐堤港商城2期1层B115号', 120.146659, 30.312742, 290, 13519, 9529, 49, '11:00-22:00', '2021-12-22 19:17:15', '2022-01-11 16:12:51');
INSERT INTO `tb_shop` VALUES (5, '海底捞火锅(水晶城购物中心店）', 1, 'https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg,https://img.meituan.net/msmerchant/59b7eff9b60908d52bd4aea9ff356e6d145920.jpg,https://qcloud.dpfile.com/pc/Qe2PTEuvtJ5skpUXKKoW9OQ20qc7nIpHYEqJGBStJx0mpoyeBPQOJE4vOdYZwm9AuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '上塘路458号水晶城购物中心F6', 120.15778, 30.310633, 104, 4125, 2764, 49, '10:00-07:00', '2021-12-22 19:20:58', '2022-01-11 16:13:01');
INSERT INTO `tb_shop` VALUES (6, '幸福里老北京涮锅（丝联店）', 1, 'https://img.meituan.net/msmerchant/e71a2d0d693b3033c15522c43e03f09198239.jpg,https://img.meituan.net/msmerchant/9f8a966d60ffba00daf35458522273ca658239.jpg,https://img.meituan.net/msmerchant/ef9ca5ef6c05d381946fe4a9aa7d9808554502.jpg', '拱宸桥/上塘', '金华南路189号丝联166号', 120.148603, 30.318618, 130, 9531, 7324, 46, '11:00-13:50,17:00-20:50', '2021-12-22 19:24:53', '2022-01-11 16:13:09');
INSERT INTO `tb_shop` VALUES (7, '炉鱼(拱墅万达广场店)', 1, 'https://img.meituan.net/msmerchant/909434939a49b36f340523232924402166854.jpg,https://img.meituan.net/msmerchant/32fd2425f12e27db0160e837461c10303700032.jpg,https://img.meituan.net/msmerchant/f7022258ccb8dabef62a0514d3129562871160.jpg', '北部新城', '杭行路666号万达商业中心4幢2单元409室(铺位号4005)', 120.124691, 30.336819, 85, 2631, 1320, 47, '00:00-24:00', '2021-12-22 19:40:52', '2022-01-11 16:13:19');
INSERT INTO `tb_shop` VALUES (8, '浅草屋寿司（运河上街店）', 1, 'https://img.meituan.net/msmerchant/cf3dff697bf7f6e11f4b79c4e7d989e4591290.jpg,https://img.meituan.net/msmerchant/0b463f545355c8d8f021eb2987dcd0c8567811.jpg,https://img.meituan.net/msmerchant/c3c2516939efaf36c4ccc64b0e629fad587907.jpg', '运河上街', '拱墅区金华路80号运河上街B1', 120.150526, 30.325231, 88, 2406, 1206, 46, ' 11:00-21:30', '2021-12-22 19:51:06', '2022-01-11 16:13:25');
INSERT INTO `tb_shop` VALUES (9, '羊老三羊蝎子牛仔排北派炭火锅(运河上街店)', 1, 'https://p0.meituan.net/biztone/163160492_1624251899456.jpeg,https://img.meituan.net/msmerchant/e478eb16f7e31a7f8b29b5e3bab6de205500837.jpg,https://img.meituan.net/msmerchant/6173eb1d18b9d70ace7fdb3f2dd939662884857.jpg', '运河上街', '台州路2号运河上街购物中心F5', 120.150598, 30.325251, 101, 2763, 1363, 44, '11:00-21:30', '2021-12-22 19:53:59', '2022-01-11 16:13:34');
INSERT INTO `tb_shop` VALUES (10, '开乐迪KTV（运河上街店）', 2, 'https://p0.meituan.net/joymerchant/a575fd4adb0b9099c5c410058148b307-674435191.jpg,https://p0.meituan.net/merchantpic/68f11bf850e25e437c5f67decfd694ab2541634.jpg,https://p0.meituan.net/dpdeal/cb3a12225860ba2875e4ea26c6d14fcc197016.jpg', '运河上街', '台州路2号运河上街购物中心F4', 120.149093, 30.324666, 67, 26891, 902, 37, '00:00-24:00', '2021-12-22 20:25:16', '2021-12-22 20:25:16');
INSERT INTO `tb_shop` VALUES (11, 'INLOVE KTV(水晶城店)', 2, 'https://p0.meituan.net/dpmerchantpic/53e74b200211d68988a4f02ae9912c6c1076826.jpg,https://qcloud.dpfile.com/pc/4iWtIvzLzwM2MGgyPu1PCDb4SWEaKqUeHm--YAt1EwR5tn8kypBcqNwHnjg96EvT_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/WZsJWRI447x1VG2x48Ujgu7vwqksi_9WitdKI4j3jvIgX4MZOpGNaFtM93oSSizbGybIjx5eX6WNgCPvcASYAw.jpg', '水晶城', '上塘路458号水晶城购物中心6层', 120.15853, 30.310002, 75, 35977, 5684, 47, '11:30-06:00', '2021-12-22 20:29:02', '2021-12-22 20:39:00');
INSERT INTO `tb_shop` VALUES (12, '魅(杭州远洋乐堤港店)', 2, 'https://p0.meituan.net/dpmerchantpic/63833f6ba0393e2e8722420ef33f3d40466664.jpg,https://p0.meituan.net/dpmerchantpic/ae3c94cc92c529c4b1d7f68cebed33fa105810.png,', '远洋乐堤港', '丽水路58号远洋乐堤港F4', 120.14983, 30.31211, 88, 6444, 235, 46, '10:00-02:00', '2021-12-22 20:34:34', '2021-12-22 20:34:34');
INSERT INTO `tb_shop` VALUES (13, '讴K拉量贩KTV(北城天地店)', 2, 'https://p1.meituan.net/merchantpic/598c83a8c0d06fe79ca01056e214d345875600.jpg,https://qcloud.dpfile.com/pc/HhvI0YyocYHRfGwJWqPQr34hRGRl4cWdvlNwn3dqghvi4WXlM2FY1te0-7pE3Wb9_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/F5ZVzZaXFE27kvQzPnaL4V8O9QCpVw2nkzGrxZE8BqXgkfyTpNExfNG5CEPQX4pjGybIjx5eX6WNgCPvcASYAw.jpg', 'D32天阳购物中心', '湖州街567号北城天地5层', 120.130453, 30.327655, 58, 18997, 1857, 41, '12:00-02:00', '2021-12-22 20:38:54', '2021-12-22 20:40:04');
INSERT INTO `tb_shop` VALUES (14, '星聚会KTV(拱墅区万达店)', 2, 'https://p0.meituan.net/dpmerchantpic/f4cd6d8d4eb1959c3ea826aa05a552c01840451.jpg,https://p0.meituan.net/dpmerchantpic/2efc07aed856a8ab0fc75c86f4b9b0061655777.jpg,https://qcloud.dpfile.com/pc/zWfzzIorCohKT0bFwsfAlHuayWjI6DBEMPHHncmz36EEMU9f48PuD9VxLLDAjdoU_Gd2X_f-v9T8Yj4uLt25Gg.jpg', '北部新城', '杭行路666号万达广场C座1-2F', 120.128958, 30.337252, 60, 17771, 685, 47, '10:00-22:00', '2021-12-22 20:48:54', '2021-12-22 20:48:54');

-- ----------------------------
-- Table structure for tb_shop_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop_type`;
CREATE TABLE `tb_shop_type`  (
                                 `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                                 `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
                                 `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
                                 `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
                                 `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop_type
-- ----------------------------
INSERT INTO `tb_shop_type` VALUES (1, '美食', '/types/ms.png', 1, '2021-12-22 20:17:47', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (2, 'KTV', '/types/KTV.png', 2, '2021-12-22 20:18:27', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (3, '丽人·美发', '/types/lrmf.png', 3, '2021-12-22 20:18:48', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (4, '健身运动', '/types/jsyd.png', 10, '2021-12-22 20:19:04', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (5, '按摩·足疗', '/types/amzl.png', 5, '2021-12-22 20:19:27', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (6, '美容SPA', '/types/spa.png', 6, '2021-12-22 20:19:35', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (7, '亲子游乐', '/types/qzyl.png', 7, '2021-12-22 20:19:53', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (8, '酒吧', '/types/jiuba.png', 8, '2021-12-22 20:20:02', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (9, '轰趴馆', '/types/hpg.png', 9, '2021-12-22 20:20:08', '2021-12-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (10, '美睫·美甲', '/types/mjmj.png', 4, '2021-12-22 20:21:46', '2021-12-23 11:24:31');

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
                            `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                            `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
                            `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
                            `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
                            `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
                            `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            PRIMARY KEY (`id`) USING BTREE,
                            UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` VALUES (1, '13686869696', '', '小鱼同学', '/imgs/blogs/blog1.jpg', '2021-12-24 10:27:19', '2022-01-11 16:04:00');
INSERT INTO `tb_user` VALUES (2, '13838411438', '', '可可今天不吃肉', '/imgs/icons/kkjtbcr.jpg', '2021-12-24 15:14:39', '2021-12-28 19:58:04');
INSERT INTO `tb_user` VALUES (4, '13456789011', '', 'user_slxaxy2au9f3tanffaxr', '', '2022-01-07 12:07:53', '2022-01-07 12:07:53');
INSERT INTO `tb_user` VALUES (5, '13456789001', '', 'user_n0bb8mwwg4', '', '2022-01-07 16:11:33', '2022-01-07 16:11:33');
INSERT INTO `tb_user` VALUES (6, '13912340001', '', '美食探险家', '/imgs/icons/explorer.jpg', '2022-01-10 08:30:00', '2022-01-10 08:30:00');
INSERT INTO `tb_user` VALUES (7, '13912340002', '', '咖啡控', '/imgs/icons/coffee.jpg', '2022-01-11 12:15:00', '2022-01-11 12:15:00');
INSERT INTO `tb_user` VALUES (8, '13912340003', '', '甜品少女', '/imgs/icons/sweets.jpg', '2022-01-12 18:20:00', '2022-01-12 18:20:00');
INSERT INTO `tb_user` VALUES (9, '13912340004', '', '健身达人', '/imgs/icons/fitness.jpg', '2022-01-13 09:45:00', '2022-01-13 09:45:00');
INSERT INTO `tb_user` VALUES (10, '13912340005', '', '夜猫子K歌王', '/imgs/icons/k歌.jpg', '2022-01-14 22:10:00', '2022-01-14 22:10:00');
INSERT INTO `tb_user` VALUES (11, '13912340006', '', '火锅星人', '/imgs/icons/hotpot.jpg', '2022-01-15 11:00:00', '2022-01-15 11:00:00');
INSERT INTO `tb_user` VALUES (12, '13912340007', '', '日料控', '/imgs/icons/sushi.jpg', '2022-01-16 14:30:00', '2022-01-16 14:30:00');
INSERT INTO `tb_user` VALUES (13, '13912340008', '', '旅行摄影师', '/imgs/icons/travel.jpg', '2022-01-17 07:20:00', '2022-01-17 07:20:00');
INSERT INTO `tb_user` VALUES (14, '13912340009', '', '奶茶续命', '/imgs/icons/milktea.jpg', '2022-01-18 16:45:00', '2022-01-18 16:45:00');
INSERT INTO `tb_user` VALUES (15, '13912340010', '', '老饕餮', '/imgs/icons/gourmet.jpg', '2022-01-19 19:00:00', '2022-01-19 19:00:00');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
                                 `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
                                 `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
                                 `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
                                 `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
                                 `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
                                 `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
                                 `birthday` date NULL DEFAULT NULL COMMENT '生日',
                                 `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
                                 `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
                                 `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------
INSERT INTO `tb_user_info` VALUES (1, '杭州', '喜欢探店拍照的小鱼', 128, 95, 0, '1995-03-12', 320, 3, '2021-12-24 10:27:19', '2022-01-11 16:04:00');
INSERT INTO `tb_user_info` VALUES (2, '上海', '不吃肉会死星人', 256, 180, 1, '1997-08-22', 580, 5, '2021-12-24 15:14:39', '2021-12-28 19:58:04');
INSERT INTO `tb_user_info` VALUES (4, '北京', '佛系用户', 10, 5, 0, '2000-01-01', 50, 0, '2022-01-07 12:07:53', '2022-01-07 12:07:53');
INSERT INTO `tb_user_info` VALUES (5, '深圳', '新手上路', 3, 2, 1, '1999-06-15', 20, 0, '2022-01-07 16:11:33', '2022-01-07 16:11:33');
INSERT INTO `tb_user_info` VALUES (6, '杭州', '哪里有好吃的？找我', 45, 32, 0, '1994-11-05', 210, 2, '2022-01-10 08:30:00', '2022-01-10 08:30:00');
INSERT INTO `tb_user_info` VALUES (7, '成都', '咖啡重度依赖', 78, 56, 1, '1996-02-28', 150, 1, '2022-01-11 12:15:00', '2022-01-11 12:15:00');
INSERT INTO `tb_user_info` VALUES (8, '广州', '嗜甜如命', 112, 88, 1, '1998-07-19', 430, 4, '2022-01-12 18:20:00', '2022-01-12 18:20:00');
INSERT INTO `tb_user_info` VALUES (9, '杭州', '撸铁使我快乐', 67, 41, 0, '1993-12-03', 90, 0, '2022-01-13 09:45:00', '2022-01-13 09:45:00');
INSERT INTO `tb_user_info` VALUES (10, '上海', 'KTV麦霸，组团请叫我', 201, 135, 1, '1995-09-17', 670, 6, '2022-01-14 22:10:00', '2022-01-14 22:10:00');
INSERT INTO `tb_user_info` VALUES (11, '重庆', '无火锅不欢', 89, 64, 0, '1997-05-24', 290, 2, '2022-01-15 11:00:00', '2022-01-15 11:00:00');
INSERT INTO `tb_user_info` VALUES (12, '杭州', '日料深度爱好者', 55, 43, 0, '1992-01-20', 180, 1, '2022-01-16 14:30:00', '2022-01-16 14:30:00');
INSERT INTO `tb_user_info` VALUES (13, '云南', '旅拍中，随缘更新', 320, 110, 1, '1994-04-09', 780, 7, '2022-01-17 07:20:00', '2022-01-17 07:20:00');
INSERT INTO `tb_user_info` VALUES (14, '长沙', '奶茶测评师', 134, 97, 1, '1999-10-31', 360, 3, '2022-01-18 16:45:00', '2022-01-18 16:45:00');
INSERT INTO `tb_user_info` VALUES (15, '杭州', '跟着我吃遍杭城', 205, 142, 0, '1988-08-08', 920, 8, '2022-01-19 19:00:00', '2022-01-19 19:00:00');

-- ----------------------------
-- Table structure for tb_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher`;
CREATE TABLE `tb_voucher`  (
                               `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '商铺id',
                               `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '代金券标题',
                               `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
                               `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
                               `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
                               `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
                               `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,秒杀券',
                               `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
                               `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher
-- ----------------------------
INSERT INTO `tb_voucher` VALUES (1, 1, '50元代金券', '周一至周日均可使用', '全场通用\\n无需预约\\n可无限叠加\\不兑现、不找零\\n仅限堂食', 4750, 5000, 0, 1, '2022-01-04 09:42:39', '2022-01-04 09:43:31');
INSERT INTO `tb_voucher` VALUES (2, 2, '20元代金券', '满100可用', '仅限堂食，每桌限用1张', 2000, 2000, 0, 1, '2022-02-01 00:00:00', '2022-02-01 00:00:00');
INSERT INTO `tb_voucher` VALUES (3, 3, '30元代金券', '满150可用', '可叠加使用', 3000, 3000, 0, 1, '2022-02-02 00:00:00', '2022-02-02 00:00:00');
INSERT INTO `tb_voucher` VALUES (4, 5, '50元代金券', '满200可用', '不可与其他优惠同享', 5000, 5000, 0, 1, '2022-02-03 00:00:00', '2022-02-03 00:00:00');
INSERT INTO `tb_voucher` VALUES (5, 7, '10元代金券', '无门槛', '每人限领2张', 1000, 1000, 0, 1, '2022-02-04 00:00:00', '2022-02-04 00:00:00');
INSERT INTO `tb_voucher` VALUES (6, 10, '8折券', '最高抵扣50元', '仅限包厢使用', 0, 0, 0, 1, '2022-02-05 00:00:00', '2022-02-05 00:00:00');
INSERT INTO `tb_voucher` VALUES (7, 1, '1元秒杀50元代金券', '限时限量', '每人限购1张，仅限周三使用', 100, 5000, 1, 1, '2022-02-06 00:00:00', '2022-02-06 00:00:00');
INSERT INTO `tb_voucher` VALUES (8, 4, '1元秒杀100元代金券', '限时限量', '仅限晚市使用，每桌限用1张', 100, 10000, 1, 1, '2022-02-07 00:00:00', '2022-02-07 00:00:00');
INSERT INTO `tb_voucher` VALUES (9, 6, '9.9元秒杀价值68元招牌菜', '限时限量', '仅限堂食，每桌限用1份', 990, 6800, 1, 1, '2022-02-08 00:00:00', '2022-02-08 00:00:00');
INSERT INTO `tb_voucher` VALUES (10, 14, '5元秒杀88元欢唱套餐', '限时限量', '仅限工作日下午场', 500, 8800, 1, 1, '2022-02-09 00:00:00', '2022-02-09 00:00:00');
INSERT INTO `tb_voucher` VALUES (11, 9, '0.1元秒杀凉菜一份', '限时限量', '每桌限用1份，仅限午餐', 10, 1800, 1, 1, '2022-02-10 00:00:00', '2022-02-10 00:00:00');

-- ----------------------------
-- Table structure for tb_voucher_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher_order`;
CREATE TABLE `tb_voucher_order`  (
                                     `id` bigint(20) NOT NULL COMMENT '主键',
                                     `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
                                     `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的代金券id',
                                     `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
                                     `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
                                     `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
                                     `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
                                     `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
                                     `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
                                     `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                     PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher_order
-- ----------------------------
INSERT INTO `tb_voucher_order` VALUES (1001, 1, 7, 1, 3, '2022-02-10 10:05:00', '2022-02-10 10:05:00', '2022-02-10 19:30:00', NULL, '2022-02-10 19:30:00');
INSERT INTO `tb_voucher_order` VALUES (1002, 2, 7, 1, 3, '2022-02-10 10:08:00', '2022-02-10 10:08:00', '2022-02-10 12:15:00', NULL, '2022-02-10 12:15:00');
INSERT INTO `tb_voucher_order` VALUES (1003, 6, 8, 2, 1, '2022-02-11 17:20:00', NULL, NULL, NULL, '2022-02-11 17:20:00');
INSERT INTO `tb_voucher_order` VALUES (1004, 7, 8, 1, 3, '2022-02-11 16:55:00', '2022-02-11 17:00:00', '2022-02-11 20:00:00', NULL, '2022-02-11 20:00:00');
INSERT INTO `tb_voucher_order` VALUES (1005, 8, 9, 3, 3, '2022-02-12 11:15:00', '2022-02-12 11:20:00', '2022-02-12 12:30:00', NULL, '2022-02-12 12:30:00');
INSERT INTO `tb_voucher_order` VALUES (1006, 9, 9, 1, 4, '2022-02-12 11:05:00', NULL, NULL, '2022-02-12 11:50:00', '2022-02-12 11:50:00');
INSERT INTO `tb_voucher_order` VALUES (1007, 10, 10, 1, 3, '2022-02-13 13:30:00', '2022-02-13 13:35:00', '2022-02-13 15:20:00', NULL, '2022-02-13 15:20:00');
INSERT INTO `tb_voucher_order` VALUES (1008, 11, 10, 1, 3, '2022-02-13 14:00:00', '2022-02-13 14:05:00', '2022-02-13 16:45:00', NULL, '2022-02-13 16:45:00');
INSERT INTO `tb_voucher_order` VALUES (1009, 12, 11, 1, 1, '2022-02-14 10:45:00', NULL, NULL, NULL, '2022-02-14 10:45:00');
INSERT INTO `tb_voucher_order` VALUES (1010, 13, 11, 2, 3, '2022-02-14 10:50:00', '2022-02-14 10:55:00', '2022-02-14 13:10:00', NULL, '2022-02-14 13:10:00');

SET FOREIGN_KEY_CHECKS = 1;