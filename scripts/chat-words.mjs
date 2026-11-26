/**
 * First-party, not from Wanxiang: everyday chat words whose Wanxiang weight is
 * multiplied by CHAT_BOOST. Wanxiang counts written text, where 这些 is twenty
 * times as common as 谢谢; in a message it is the other way round, and on the
 * nine-key pad the two share every key (943 943). Nothing is invented: a word
 * missing from the pinned data under the spelling given here is not added.
 * Written from general knowledge of chat Chinese, not from the benchmark's
 * misses; bench/pinyin/README.md reports held-out results with and without the
 * phrases that contain one of these words.
 *
 * Shared by the Java engine's asset (build-pinyin-dict.mjs, 0.8.9) and Rime's
 * first-party chat table (build-rime-chat.mjs, 0.9.0). MIT.
 */
export const CHAT_WORDS = `
谢谢 xiexie|谢谢你 xiexieni|谢啦 xiela|多谢 duoxie|好的 haode|好吧 haoba|好啊 haoa|好呀 haoya|嗯嗯 enen
哈哈 haha|哈哈哈 hahaha|嘿嘿 heihei|晚安 wanan|早安 zaoan|早上好 zaoshanghao|晚上好 wanshanghao
在吗 zaima|在干嘛 zaiganma|干嘛 ganma|怎么了 zenmele|没事 meishi|没事了 meishile|没关系 meiguanxi
不客气 bukeqi|辛苦了 xinkule|拜拜 baibai|再见 zaijian|回头见 huitoujian|明天见 mingtianjian
等一下 dengyixia|等会 denghui|马上 mashang|稍等 shaodeng|收到 shoudao|知道了 zhidaole|是的 shide
对的 duide|对啊 duia|真的吗 zhendema|可以吗 keyima|好吗 haoma|行吗 xingma|吃了吗 chilema
吃饭了吗 chifanlema|睡了吗 shuilema|到了吗 daolema|到家了 daojiale|我到了 wodaole|出发了 chufale
想你 xiangni|想你了 xiangnile|爱你 aini|亲爱的 qinaide|宝贝 baobei|老公 laogong|老婆 laopo
早点睡 zaodianshui|早点休息 zaodianxiuxi|注意安全 zhuyianquan|路上小心 lushangxiaoxin|慢点 mandian
好开心 haokaixin|太好了 taihaole|太棒了 taibangle|加油 jiayou|好累 haolei|累了 leile|困了 kunle
饿了 ele|吃啥 chisha|去哪 quna|在哪 zaina|几点 jidian|几点了 jidianle|怎么办 zenmeban
不知道 buzhidao|不用了 buyongle|算了 suanle|都行 douxing|可以的 keyide|没问题 meiwenti
好久不见 haojiubujian|生日快乐 shengrikuaile|新年快乐 xinniankuaile|恭喜 gongxi|拜托 baituo
麻烦你了 mafannile|不好意思 buhaoyisi|对不起 duibuqi|抱歉 baoqian|哈喽 halou|么么哒 memeda
`.trim().split(/[|\n]/).map((pair) => pair.trim().split(" ")).filter((pair) => pair.length === 2);
