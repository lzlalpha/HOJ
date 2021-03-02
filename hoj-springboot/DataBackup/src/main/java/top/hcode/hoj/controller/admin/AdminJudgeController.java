package top.hcode.hoj.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.judge.remote.RemoteJudgeDispatcher;
import top.hcode.hoj.judge.self.JudgeDispatcher;
import top.hcode.hoj.pojo.entity.*;
import top.hcode.hoj.service.impl.*;
import top.hcode.hoj.utils.Constants;

import java.util.LinkedList;
import java.util.List;

/**
 * @Author: Himit_ZH
 * @Date: 2021/1/3 14:09
 * @Description:
 */

@RestController
@RequestMapping("/admin/judge")
@RefreshScope
public class AdminJudgeController {

    @Autowired
    private JudgeServiceImpl judgeService;

    @Autowired
    private ProblemCountServiceImpl problemCountService;

    @Autowired
    private UserAcproblemServiceImpl userAcproblemService;

    @Autowired
    private ContestRecordServiceImpl contestRecordService;

    @Autowired
    private JudgeCaseServiceImpl judgeCaseService;

    @Autowired
    private ProblemServiceImpl problemService;

    @Value("${hoj.judge.token}")
    private String judgeToken;

    @Autowired
    private JudgeDispatcher judgeDispatcher;

    @Autowired
    private RemoteJudgeDispatcher remoteJudgeDispatcher;

    @GetMapping("/rejudge")
    @RequiresAuthentication
    @RequiresRoles("root")  // 只有超级管理员能操作
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CommonResult rejudge(@RequestParam("submitId") Long submitId) {
        Judge judge = judgeService.getById(submitId);

        // 如果是非比赛题目
        if (judge.getCid() == 0 && judge.getCpid() == 0) {
            // 重判前，需要将该题目对应记录表一并更新
            // 更新该题目的提交统计表problem_count
            String columnName = Constants.Judge.getTableColumnNameByStatus(judge.getStatus()); // 获取要修改的字段名
            if (columnName != null) {
                UpdateWrapper<ProblemCount> problemCountUpdateWrapper = new UpdateWrapper<>();
                // 重判需要减掉之前的判题结果
                problemCountUpdateWrapper.setSql(columnName + "=" + columnName + "-1,total=total-1")
                        .eq("pid", judge.getPid());
                problemCountService.update(problemCountUpdateWrapper);
            }
            // 如果该题已经是AC通过状态，更新该题目的用户ac做题表 user_acproblem
            if (judge.getStatus().intValue() == Constants.Judge.STATUS_ACCEPTED.getStatus().intValue()) {
                QueryWrapper<UserAcproblem> userAcproblemQueryWrapper = new QueryWrapper<>();
                userAcproblemQueryWrapper.eq("pid", judge.getPid()).eq("uid", judge.getUid());
                userAcproblemService.remove(userAcproblemQueryWrapper);
            }
        }

        // 清除该提交对应的测试点结果
        QueryWrapper<JudgeCase> judgeCaseQueryWrapper = new QueryWrapper<>();
        judgeCaseQueryWrapper.eq("submit_id", submitId);
        judgeCaseService.remove(judgeCaseQueryWrapper);

        // 设置默认值
        judge.setStatus(Constants.Judge.STATUS_PENDING.getStatus()); // 开始进入判题队列
        judge.setJudger(null).setTime(null).setMemory(null).setErrorMessage(null);
        boolean result = judgeService.updateById(judge);
        if (result) {
            // 调用判题服务
            Problem problem = problemService.getById(judge.getPid());
            if (problem.getIsRemote()) { // 如果是远程oj判题
                remoteJudgeDispatcher.sendTask(judge.getSubmitId(), judge.getPid(), judgeToken, problem.getProblemId(),
                        judge.getCid() == 0, 1);
            } else {
                judgeDispatcher.sendTask(judge.getSubmitId(), judge.getPid(), judgeToken, judge.getCid() == 0, 1);
            }
            return CommonResult.successResponse(judge, "重判成功！该提交已进入判题队列！");
        } else {
            return CommonResult.successResponse(judge, "重判失败！请重新尝试！");
        }
    }

    @GetMapping("/rejudge-contest-problem")
    @RequiresAuthentication
    @RequiresRoles("root")  // 只有超级管理员能操作
    @Transactional
    public CommonResult rejudgeContestProblem(@RequestParam("cid") Long cid, @RequestParam("pid") Long pid) {


        QueryWrapper<Judge> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("cid", cid).eq("pid", pid);
        List<Judge> rejudgeList = judgeService.list(queryWrapper);

        if (rejudgeList.size() == 0) {
            return CommonResult.errorResponse("当前该题目无提交，不可重判！");
        }
        List<Long> submitIdList = new LinkedList<>();
        // 全部设置默认值
        for (Judge judge : rejudgeList) {
            judge.setStatus(Constants.Judge.STATUS_PENDING.getStatus()); // 开始进入判题队列
            judge.setJudger(null).setTime(null).setMemory(null).setErrorMessage(null);
            submitIdList.add(judge.getSubmitId());
        }
        boolean resetJudgeResult = judgeService.updateBatchById(rejudgeList);
        // 清除每个提交对应的测试点结果
        QueryWrapper<JudgeCase> judgeCaseQueryWrapper = new QueryWrapper<>();
        judgeCaseQueryWrapper.in("submit_id", submitIdList);
        judgeCaseService.remove(judgeCaseQueryWrapper);
        // 将对应比赛记录设置成默认值
        UpdateWrapper<ContestRecord> updateWrapper = new UpdateWrapper<>();
        updateWrapper.in("submit_id", submitIdList).setSql("status=null,score=null");
        boolean resetContestRecordResult = contestRecordService.update(updateWrapper);

        if (resetContestRecordResult && resetJudgeResult) {
            // 调用重判服务
            Problem problem = problemService.getById(pid);
            if (problem.getIsRemote()) { // 如果是远程oj判题
                for (Judge judge : rejudgeList) {
                    // 进入重判队列，等待调用判题服务
                    remoteJudgeDispatcher.sendTask(judge.getSubmitId(), judge.getPid(), judgeToken, problem.getProblemId(),
                            judge.getCid() == 0, 1);
                }
            } else {
                for (Judge judge : rejudgeList) {
                    // 进入重判队列，等待调用判题服务
                    judgeDispatcher.sendTask(judge.getSubmitId(), judge.getPid(), judgeToken, judge.getCid() == 0, 1);
                }
            }

            return CommonResult.successResponse(null, "重判成功！该题目对应的全部提交已进入判题队列！");
        } else {
            return CommonResult.errorResponse("重判失败！请重新尝试！");
        }

    }
}