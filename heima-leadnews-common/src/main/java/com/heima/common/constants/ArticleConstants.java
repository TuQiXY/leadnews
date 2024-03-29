package com.heima.common.constants;

public class ArticleConstants {
    public static final Short LOADTYPE_LOAD_MORE = 1;//加载更多
    public static final Short LOADTYPE_LOAD_NEW = 2;//加载最新
    public static final String DEFAULT_TAG = "__all__";//默认频道

    public static final String ARTICLE_ES_SYNC_TOPIC = "article.es.sync.topic";
    /**
     *  点赞权重
     */
    public static final Integer HOT_ARTICLE_LIKE_WEIGHT = 3;
    /**
     * 评论权重
     */
    public static final Integer HOT_ARTICLE_COMMENT_WEIGHT = 5;
    /**
     *  收藏权重
     */
    public static final Integer HOT_ARTICLE_COLLECTION_WEIGHT = 8;
    /**
     *  热文章的第一页
     */
    public static final String HOT_ARTICLE_FIRST_PAGE = "hot_article_first_page_";
}
