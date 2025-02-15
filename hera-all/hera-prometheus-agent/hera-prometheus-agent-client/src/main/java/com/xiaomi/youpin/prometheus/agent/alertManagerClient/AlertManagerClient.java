package com.xiaomi.youpin.prometheus.agent.alertManagerClient;

import com.alibaba.nacos.api.config.annotation.NacosValue;
import com.google.gson.Gson;
import com.xiaomi.youpin.prometheus.agent.Commons;
import com.xiaomi.youpin.prometheus.agent.client.Client;
import com.xiaomi.youpin.prometheus.agent.entity.RuleAlertEntity;
import com.xiaomi.youpin.prometheus.agent.param.alertManager.AlertManagerConfig;
import com.xiaomi.youpin.prometheus.agent.param.alertManager.Group;
import com.xiaomi.youpin.prometheus.agent.param.alertManager.Rule;
import com.xiaomi.youpin.prometheus.agent.service.prometheus.RuleAlertService;
import com.xiaomi.youpin.prometheus.agent.util.FileUtil;
import com.xiaomi.youpin.prometheus.agent.util.Http;
import com.xiaomi.youpin.prometheus.agent.util.YamlUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.xiaomi.youpin.prometheus.agent.Commons.HTTP_POST;

@Service
@Slf4j
public class AlertManagerClient implements Client {

    @NacosValue(value = "${job.alertManager.reloadAddr}", autoRefreshed = true)
    private String reloadAddr;

    @NacosValue(value = "${job.alertManager.filePath}", autoRefreshed = true)
    private String filePath;

    private String backFilePath;

    @NacosValue(value = "${job.alertManager.enabled}", autoRefreshed = true)
    private String enabled;

    //第一次GetLocalConfigs后置位true
    private boolean firstInitSign = false;

    @Autowired
    RuleAlertService ruleAlertService;

    private Map<String, List<Rule>> localRuleList = new HashMap<>();

    private static final Gson gson = new Gson();

    @PostConstruct
    public void init() {
        backFilePath = filePath + ".bak";
        if (enabled.equals("true")) {
            GetLocalConfigs();
            CompareAndReload();
        } else {
            log.info("AlertManagerClient not init");
        }
    }

    @Override
    public void GetLocalConfigs() {
        //定时请求数据库查找所有pending状态的未删除的alert
        new ScheduledThreadPoolExecutor(1).scheduleAtFixedRate(() -> {
            try {
                log.info("AlertManagerClient start GetLocalConfigs");
                List<RuleAlertEntity> allRuleAlertList = ruleAlertService.GetAllRuleAlertList();
                //先清空上一次结果
                localRuleList.clear();
                log.info("AlertManagerClient GetLocalConfigs allRuleAlertList: {}", allRuleAlertList);
                allRuleAlertList.forEach(item -> {
                    //加上group分组
                    String tmpGroup = item.getAlert_group();
                    Rule rule = new Rule();
                    rule.setAlert(item.getName());
                    rule.setAnnotations(transAnnotation2Map(item.getAnnotation()));
                    rule.setLabels(transLabel2Map(item.getLabels()));
                    rule.setExpr(item.getExpr());
                    rule.setFor(item.getAlertFor());

                    if (localRuleList.containsKey(tmpGroup)) {
                        localRuleList.get(tmpGroup).add(rule);
                    } else {
                        List<Rule> rules = new ArrayList<>();
                        localRuleList.put(tmpGroup, rules);
                        localRuleList.get(tmpGroup).add(rule);
                    }

                });
                log.info("AlertManagerClient GetLocalConfigs done!");
                firstInitSign = true;
            } catch (Exception e) {
                log.error("AlertManagerClient GetLocalConfigs error:{}", e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    @Override
    @SneakyThrows
    public void CompareAndReload() {

        new ScheduledThreadPoolExecutor(1).scheduleAtFixedRate(() -> {
            //如果有变动，调用reload接口，一期直接reload
            //读取本地rule配置文件
            try {
                //如果localRuleList为空，说明数据库中为空记录，直接返回
                if (localRuleList.size() == 0) {
                    log.info("localRuleList is empty and no need reload");
                    return;
                }
                if (!firstInitSign) {
                    log.info("AlertManagerClient CompareAndReload waiting..");
                    return;
                }
                log.info("AlertManagerClient start CompareAndReload");
                AlertManagerConfig ruleAlertConfig = getRuleAlertConfig(filePath);
                log.info("ruleAlertConfig: {}", ruleAlertConfig);
                log.info("localrulelist: {}", localRuleList);

                List<Group> group = new ArrayList<>();
                for (Map.Entry<String, List<Rule>> entry : localRuleList.entrySet()) {
                    Group tmpGroup = new Group();
                    tmpGroup.setName(entry.getKey());
                    tmpGroup.setRules(entry.getValue());
                    group.add(tmpGroup);
                }
                ruleAlertConfig.setGroups(group);
                writeAlertManagerConfig2Yaml(ruleAlertConfig);

                log.info("AlertManagerClient request reload url :{}", reloadAddr);
                String getReloadRes = Http.innerRequest("", reloadAddr, HTTP_POST);
                log.info("AlertManagerClient request reload res :{}", getReloadRes);
                if (getReloadRes.equals("200")) {
                    log.info("AlertManagerClient request reload success");
                    //成功后，删除备份
                    deleteBackConfig();
                } else {
                    //如果reload失败，用备份恢复配置
                    log.info("AlertManagerClient request reload fail and begin rollback config");
                    boolean rollbackRes = restoreConfiguration(backFilePath, filePath);
                    log.info("AlertManagerClient request reload fail and rollbackRes: {}", rollbackRes);

                }
            } catch (Exception e) {
                log.error("AlertManagerClient CompareAndReload error: {}", e);
            }

        }, 0, 30, TimeUnit.SECONDS);
    }

    //将labelString转换成map
    private Map<String, String> transLabel2Map(String labels) {
        //按,分割后,再按=分割
        Map<String, String> labelMap = new HashMap<>();
        try {
            Arrays.stream(labels.split(",")).forEach(item -> {
                String[] split = item.split("=");
                labelMap.put(split[0], split[1]);
            });
            return labelMap;
        }catch (Exception e){
            log.error("AlertManagerClient transLabel2Map error: {}", e);
            return labelMap;
        }

    }

    //将annotationString转换成map
    private Map<String, String> transAnnotation2Map(String annotations) {
        Map annotationMap = gson.fromJson(annotations, Map.class);
//        Map<String, String> annotationMap = new HashMap<>();
//        Arrays.stream(annotations.split(",")).forEach(item -> {
//            String[] split = item.split("=");
//            annotationMap.put(split[0], split[1]);
//        });
        return annotationMap;
    }

    private synchronized AlertManagerConfig getRuleAlertConfig(String path) {
        log.info("AlertManagerClient getRuleAlertConfig path : {}", path);
        String content = FileUtil.LoadFile(path);
        AlertManagerConfig alertManagerConfig = YamlUtil.toObject(content, AlertManagerConfig.class);
        log.info("AlertManagerClient config : {}", alertManagerConfig);
        //System.out.println(content);
        //转换成AlertManager配置类
        return alertManagerConfig;
    }

    private void writeAlertManagerConfig2Yaml(AlertManagerConfig alertManagerConfig) {
        //转换成yaml
        log.info("AlertManagerClient write config : {}", alertManagerConfig);
        String alertManagerYml = YamlUtil.toYaml(alertManagerConfig);
        //检验文件是否存在
        if (!isFileExists(filePath)) {
            log.error("AlertManagerClient writeAlertManagerConfig2Yaml no files here path: {}", filePath);
            return;
        }
        //备份
        backUpConfig();
        //覆盖写配置

        String writeRes = FileUtil.WriteFile(filePath, alertManagerYml);
        if (StringUtils.isEmpty(writeRes)) {
            log.error("AlertManagerClient WriteFile Error");
        }
        log.info("AlertManagerClient WriteFile res : {}", writeRes);
    }

    //备份配置文件
    private void backUpConfig() {
        //检验文件是否存在
        if (!isFileExists(filePath)) {
            log.error("AlertManagerClient backUpConfig no files here path: {}", filePath);
            return;
        }

        //如果没有备份文件则创建
        if (!isFileExists(backFilePath)) {
            log.info("AlertManagerClient backUpConfig backFile does not exist and begin create");
            FileUtil.GenerateFile(backFilePath);
        }

        //获取当前配置文件
        String content = FileUtil.LoadFile(filePath);
        //写备份
        String writeRes = FileUtil.WriteFile(backFilePath, content);
        if (StringUtils.isEmpty(writeRes)) {
            log.error("AlertManagerClient backUpConfig WriteFile Error");
        } else {
            log.info("AlertManagerClient backUpConfig WriteFile success");
        }
    }

    //reload成功后，删除备份配置
    private void deleteBackConfig() {
        //检验文件是否存在
        if (!isFileExists(backFilePath)) {
            log.error("AlertManagerClient deleteBackConfig no files here path: {}", backFilePath);
            return;
        }
        //删除备份文件
        boolean deleteRes = FileUtil.DeleteFile(backFilePath);
        if (deleteRes) {
            log.info("AlertManagerClient deleteBackConfig delete success");
        } else {
            log.error("AlertManagerClient deleteBackConfig delete fail");
        }
    }

    //校验文件是否存在
    private boolean isFileExists(String filePath) {
        return FileUtil.IsHaveFile(filePath);
    }

    //用备份文件恢复原文件
    private boolean restoreConfiguration(String oldFilePath, String newFilePath) {
        log.info("AlertManagerClient restoreConfiguration oldPath: {}, newPath: {}", oldFilePath, newFilePath);
        boolean b = FileUtil.RenameFile(oldFilePath, newFilePath);
        return b;
    }

}
