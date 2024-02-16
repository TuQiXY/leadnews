package com.heima.wemedia.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.constants.WemediaConstants;
import com.heima.common.constants.WmNewsMessageConstants;
import com.heima.common.constants.WmNewsStatus;
import com.heima.common.exception.CustomException;
import com.heima.model.common.dtos.PageResponseResult;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.dtos.WmAuditDto;
import com.heima.model.wemedia.dtos.WmNewsDto;
import com.heima.model.wemedia.dtos.WmNewsPageReqDto;
import com.heima.model.wemedia.pojos.WmMaterial;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmNewsMaterial;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.thread.WmThreadLocalUtil;
import com.heima.wemedia.mapper.WmMaterialMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmNewsMaterialMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import com.heima.wemedia.service.WmNewsService;
import com.heima.wemedia.service.WmNewsTaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class WmNewsServiceImpl  extends ServiceImpl<WmNewsMapper, WmNews> implements WmNewsService {


    @Resource
    private WmNewsAutoScanService wmNewsAutoScanService;

    @Resource
    private WmNewsTaskService wmNewsTaskService;

    @Resource
    private KafkaTemplate<String,String> kafkaTemplate;



    /**
     * 查询文章
     * @param dto
     * @return
     */
    @Override
    public ResponseResult findAll(WmNewsPageReqDto dto) {

        //1.检查参数
        if(dto == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        //分页参数检查
        dto.checkParam();
        //获取当前登录人的信息
        WmUser user = WmThreadLocalUtil.getUser();
        if(user == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        //2.分页条件查询
        IPage page = new Page(dto.getPage(),dto.getSize());
        LambdaQueryWrapper<WmNews> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        //状态精确查询
        if(dto.getStatus() != null){
            lambdaQueryWrapper.eq(WmNews::getStatus,dto.getStatus());
        }

        //频道精确查询
        if(dto.getChannelId() != null){
            lambdaQueryWrapper.eq(WmNews::getChannelId,dto.getChannelId());
        }

        //时间范围查询
        if(dto.getBeginPubDate()!=null && dto.getEndPubDate()!=null){
            lambdaQueryWrapper.between(WmNews::getPublishTime,dto.getBeginPubDate(),dto.getEndPubDate());
        }

        //关键字模糊查询
        if(StringUtils.isNotBlank(dto.getKeyword())){
            lambdaQueryWrapper.like(WmNews::getTitle,dto.getKeyword());
        }

        //查询当前登录用户的文章
        lambdaQueryWrapper.eq(WmNews::getUserId,user.getId());

        //发布时间倒序查询
        lambdaQueryWrapper.orderByDesc(WmNews::getCreatedTime);

        page = page(page,lambdaQueryWrapper);

        //3.结果返回
        ResponseResult responseResult = new PageResponseResult(dto.getPage(),dto.getSize(),(int)page.getTotal());
        responseResult.setData(page.getRecords());

        return responseResult;
    }



    /**
     * 发布修改文章或保存为草稿
     * @param dto
     * @return
     */
    @Override
    public ResponseResult submitNews(WmNewsDto dto) {

        //0.条件判断
        if(dto == null || dto.getContent() == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //1.保存或修改文章

        WmNews wmNews = new WmNews();
        //属性拷贝 属性名词和类型相同才能拷贝
        BeanUtils.copyProperties(dto,wmNews);
        //封面图片  list---> string
        if(dto.getImages() != null && dto.getImages().size() > 0){
            //[1dddfsd.jpg,sdlfjldk.jpg]-->   1dddfsd.jpg,sdlfjldk.jpg
            String imageStr = StringUtils.join(dto.getImages(), ",");
            wmNews.setImages(imageStr);
        }
        //如果当前封面类型为自动 -1
        if(dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)){
            wmNews.setType(null);
        }
        //新增获取值修改文章信息
        saveOrUpdateWmNews(wmNews);

        //2.判断是否为草稿  如果为草稿结束当前方法
        if(dto.getStatus().equals(WmNews.Status.NORMAL.getCode())){
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }

        //3.不是草稿，保存文章内容图片与素材的关系
        //获取到文章内容中的图片信息
        List<String> materials =  ectractUrlInfo(dto.getContent());
        saveRelativeInfoForContent(materials,wmNews.getId());

        //4.不是草稿，保存文章封面图片与素材的关系，如果当前布局是自动，需要匹配封面图片
        saveRelativeInfoForCover(dto,wmNews,materials);

        //5.审核文章
        /**
         *  异步审核文章
         */
        //wmNewsAutoScanService.autoScanWmNews(wmNews.getId());
        /**
         *  先提交到任务服务中
         */
        wmNewsTaskService.addNewsToTask(wmNews.getId(),wmNews.getPublishTime());

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);

    }

    /**
     * 查询文章详情
     * @param id
     * @return
     */
    @Override
    public ResponseResult findOne(Integer id) {
        //1.检查参数
        if(id == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        //2.查询文章
        WmNews wmNews = getById(id);
        if(wmNews == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        //3.结果返回
        return ResponseResult.okResult(wmNews);
    }
    @Autowired
    private WmNewsService wmNewsService;

    /**
     * 删除文章
     * @param id
     * @return
     */
    @Override
    public ResponseResult deleteNews(Integer id) {
        if (id==null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }

        WmNews wmNews=wmNewsService.getById(id);
        if (wmNews==null){
            return ResponseResult.errorResult(1002,"文章不存在");
        }

        wmNewsService.removeById(id);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /**
     * 第一个功能：如果当前封面类型为自动，则设置封面类型的数据
     * 匹配规则：
     * 1，如果内容图片大于等于1，小于3  单图  type 1
     * 2，如果内容图片大于等于3  多图  type 3
     * 3，如果内容没有图片，无图  type 0
     *
     * 第二个功能：保存封面图片与素材的关系
     * @param dto
     * @param wmNews
     * @param materials
     */
    private void saveRelativeInfoForCover(WmNewsDto dto, WmNews wmNews, List<String> materials) {

        //获取封面图片列表
        List<String> images = dto.getImages();

        //如果当前封面类型为自动，则设置封面类型的数据
        if(dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)){
            //多图
            if(materials.size() >= 3){
                wmNews.setType(WemediaConstants.WM_NEWS_MANY_IMAGE);
                images = materials.stream().limit(3).collect(Collectors.toList());
            }else if(materials.size() >= 1){
                //单图
                wmNews.setType(WemediaConstants.WM_NEWS_SINGLE_IMAGE);
                images = materials.stream().limit(1).collect(Collectors.toList());
            }else {
                //无图
                wmNews.setType(WemediaConstants.WM_NEWS_NONE_IMAGE);
            }

            //修改文章
            if(images != null && images.size() > 0){
                wmNews.setImages(StringUtils.join(images,","));
            }
            updateById(wmNews);
        }
        if(images != null && images.size() > 0){
            saveRelativeInfo(images,wmNews.getId(),WemediaConstants.WM_COVER_REFERENCE);
        }

    }


    /**
     * 处理文章内容图片与素材的关系
     * @param materials
     * @param newsId
     */
    private void saveRelativeInfoForContent(List<String> materials, Integer newsId) {
        saveRelativeInfo(materials,newsId, WemediaConstants.WM_CONTENT_REFERENCE);
    }

    @Autowired
    private WmMaterialMapper wmMaterialMapper;

    /**
     * 保存文章图片与素材的关系到数据库中
     * @param materials
     * @param newsId
     * @param type
     */
    private void saveRelativeInfo(List<String> materials, Integer newsId, Short type) {
        if(materials!=null && !materials.isEmpty()){
            //通过图片的url查询素材的id
            List<WmMaterial> dbMaterials = wmMaterialMapper.selectList(Wrappers.<WmMaterial>lambdaQuery().in(WmMaterial::getUrl, materials));

            //判断素材是否有效
            if(dbMaterials==null || dbMaterials.size() == 0){
                //手动抛出异常   第一个功能：能够提示调用者素材失效了，第二个功能，进行数据的回滚
                throw new CustomException(AppHttpCodeEnum.MATERIASL_REFERENCE_FAIL);
            }
            //如果素材数量和数据库中查询的数量不同，报错
            if(materials.size() != dbMaterials.size()){
                throw new CustomException(AppHttpCodeEnum.MATERIASL_REFERENCE_FAIL);
            }
            //只获取素材的id
            List<Integer> idList = dbMaterials.stream().map(WmMaterial::getId).collect(Collectors.toList());

            //素材和文章是多对一的关系，一个文章关联着多个素菜
            //批量保存
            wmNewsMaterialMapper.saveRelations(idList,newsId,type);
        }

    }


    /**
     * 提取文章内容中的图片信息
     * @param content
     * @return
     */
    private List<String> ectractUrlInfo(String content) {
        List<String> materials = new ArrayList<>();

        List<Map> maps = JSON.parseArray(content, Map.class);
        for (Map map : maps) {
            if(map.get("type").equals("image")){
                String imgUrl = (String) map.get("value");
                materials.add(imgUrl);
            }
        }

        return materials;
    }

    @Autowired
    private WmNewsMaterialMapper wmNewsMaterialMapper;

    /**
     * 保存或修改文章
     * @param wmNews
     */
    private void saveOrUpdateWmNews(WmNews wmNews) {
        //补全属性
        wmNews.setUserId(WmThreadLocalUtil.getUser().getId());
        wmNews.setCreatedTime(new Date());
        wmNews.setSubmitedTime(new Date());
        wmNews.setEnable((short)1);//默认上架

        if(wmNews.getId() == null){
            //保存
            save(wmNews);
        }else {
            //修改
            //删除文章图片与素材的关系
            wmNewsMaterialMapper.delete(Wrappers.<WmNewsMaterial>lambdaQuery().eq(WmNewsMaterial::getNewsId,wmNews.getId()));
            updateById(wmNews);
        }

    }


    /**
     * 文章的上下架
     * @param dto
     * @return
     */
    @Override
    public ResponseResult downOrUp(WmNewsDto dto) {
        //1.检查参数
        if(dto.getId() == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //2.查询文章
        WmNews wmNews = getById(dto.getId());
        if(wmNews == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST,"文章不存在");
        }

        //3.判断文章是否已发布
        if(!wmNews.getStatus().equals(WmNews.Status.PUBLISHED.getCode())){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"当前文章不是发布状态，不能上下架");
        }

        //4.修改文章enable
        if(dto.getEnable() != null && dto.getEnable() > -1 && dto.getEnable() < 2){
            update(Wrappers.<WmNews>lambdaUpdate().set(WmNews::getEnable,dto.getEnable())
                    .eq(WmNews::getId,wmNews.getId()));
        }

        //发送消息，通知article端修改文章配置
        if(wmNews.getArticleId() != null){
            Map<String,Object> map = new HashMap<>();
            map.put("articleId",wmNews.getArticleId());
            map.put("enable",dto.getEnable());
            kafkaTemplate.send(WmNewsMessageConstants.WM_NEWS_UP_OR_DOWN_TOPIC,JSON.toJSONString(map));
        }


        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /**
     * 自媒体文章分页条件查询
     * @param dto
     * @return
     */
    @Override
    public ResponseResult pageQuery(WmNewsPageReqDto dto) {
       //先校验参数
        dto.checkParam();
        //构造分页
        Page<WmNews> page = new Page<>(dto.getPage(),dto.getSize());

        LambdaQueryWrapper<WmNews> wrapper = Wrappers.lambdaQuery();
        //根据文章标题模糊查询
        if(!ObjectUtils.isEmpty(dto.getTitle())){
            wrapper.like(WmNews::getTitle,dto.getTitle());
        }
        //根据文章状态查询
        if(!ObjectUtils.isEmpty(dto.getStatus())){
            wrapper.eq(WmNews::getStatus,dto.getStatus());
        }
        //根据文章标题查询
        if(!ObjectUtils.isEmpty(dto.getKeyword())){
            wrapper.like(WmNews::getTitle,dto.getKeyword());
        }
        //根据频道id查询
        if(!ObjectUtils.isEmpty(dto.getChannelId())){
            wrapper.eq(WmNews::getChannelId,dto.getChannelId());
        }
        //根据时间倒序排序
        wrapper.orderByDesc(WmNews::getCreatedTime);
        //查询
        this.page(page,wrapper);

        //3.结果返回
        ResponseResult responseResult = new PageResponseResult(dto.getPage(),dto.getSize(),(int)page.getTotal());
        responseResult.setData(page.getRecords());

        return responseResult;

    }

    /**
     * 文章审核通过
     * @param auditDto
     * @return
     */
    @Override
    public ResponseResult pass(WmAuditDto auditDto) {
        //先校验参数
        Integer id = auditDto.getId();
        if(ObjectUtils.isEmpty(id)){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"文章id不能为空");
        }
        //状态只能是4
        Integer status = auditDto.getStatus();
        if(status!= WmNewsStatus.SUCCESS_HUMAN_AUDIT){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"状态只能是4");
        }
        //然后修改状态即可
        UpdateWrapper<WmNews> updateWrapper = new UpdateWrapper<>();

        updateWrapper.eq("id",id);
        updateWrapper.set("status",status);

        boolean isUpdate = this.update(updateWrapper);

        if(isUpdate){
            return ResponseResult.okResult("审核成功");
        }else{
            log.error("人工审核文章失败:{}",id);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,"审核失败");
        }




    }

    /**
     * 人工审核不通过
     * @param auditDto
     * @return
     */
    @Override
    public ResponseResult failPass(WmAuditDto auditDto) {
        //先校验参数
        Integer id = auditDto.getId();
        if(ObjectUtils.isEmpty(id)){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"文章id不能为空");
        }
        //状态只能是2
        Integer status = auditDto.getStatus();
        if(status!=WmNewsStatus.FAIL_AUDIT){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"状态只能是2");
        }
        //校验理由
        String title = auditDto.getTitle();
        String msg = auditDto.getMsg();


        if(ObjectUtils.isEmpty(title) && ObjectUtils.isEmpty(msg)){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,"驳回理由不能为空");
        }

        //修改状态，设置理由
        UpdateWrapper<WmNews> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id",id);
        updateWrapper.set("status",WmNewsStatus.FAIL_AUDIT);
        if(title==null){
            updateWrapper.set("reason",msg);
        }else{
            updateWrapper.set("reason",title);
        }

        boolean isUpdate = this.update(updateWrapper);
        if(isUpdate){
            return ResponseResult.okResult("驳回成功");
        }else{
            log.error("人工驳回文章失败:{}",id);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,"驳回失败");
        }

    }

}