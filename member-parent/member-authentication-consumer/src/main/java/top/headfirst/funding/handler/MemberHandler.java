package top.headfirst.funding.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import top.headfirst.funding.api.MySQLRemoteService;
import top.headfirst.funding.api.RedisRemoteService;
import top.headfirst.funding.config.ShortMessageProperties;
import top.headfirst.funding.constant.FundingConstant;
import top.headfirst.funding.entity.po.MemberPO;
import top.headfirst.funding.entity.vo.MemberLoginVO;
import top.headfirst.funding.entity.vo.MemberVO;
import top.headfirst.funding.util.FundingUtil;
import top.headfirst.funding.util.ResultEntity;

import javax.servlet.http.HttpSession;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Controller
public class MemberHandler {

    @Autowired
    private ShortMessageProperties shortMessageProperties;

    @Autowired
    private RedisRemoteService redisRemoteService;

    @Autowired
    private MySQLRemoteService mySQLRemoteService;

    private Logger logger = LoggerFactory.getLogger(MemberHandler.class);

    @RequestMapping("/auth/member/logout")
    public String logout(HttpSession session){
        session.invalidate();
        return "redirect:http://localhost/";
    }

    @RequestMapping("/auth/member/do/login")
    public String login(
            @RequestParam("loginacct") String loginacct,
            @RequestParam("userpswd") String userpswd,
            HttpSession session,
            Model model){
        ResultEntity<MemberPO> resultEntity = mySQLRemoteService.getMemberPOByLoginAcctRemote(loginacct);
        if (resultEntity.FAILED.equals(resultEntity.getResult())){
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,resultEntity.getMessage());
            return "member-login";
        }

        MemberPO memberPO = resultEntity.getData();
        if (memberPO == null){
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,FundingConstant.MESSAGE_LOGIN_FAILED);
            return "member-login";
        }

        String userpswdDataBase = memberPO.getUserpswd();
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        boolean matches = passwordEncoder.matches(userpswd, userpswdDataBase);
        if (!matches){
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,FundingConstant.MESSAGE_LOGIN_FAILED);
            return "member-login";
        }

        // ??????MemberLoginVO????????????Session???
        MemberLoginVO memberLoginVO = new MemberLoginVO(memberPO.getId(), memberPO.getUsername(), memberPO.getEmail());
        session.setAttribute(FundingConstant.ATTR_NAME_LOGIN_MEMBER,memberLoginVO);
        return "redirect:http://localhost/auth/member/to/center/page";
    }

    @RequestMapping("/auth/do/member/register")
    public String register(MemberVO memberVO, Model model){
        // ???????????????
        String phone_number = memberVO.getPhone_number();
        // ???Redis??????Key?????????value
        String key = FundingConstant.REDIS_CODE_PREFIX + phone_number;
        System.out.println("key=" + key);
        // ???Redis??????Key?????????value
        ResultEntity<String> resultEntity = redisRemoteService.getRedisStringValueByKeyRemote(key);

        // ??????????????????????????????
        String result = resultEntity.getResult();
        System.out.println("result=" + result);
        if (ResultEntity.FAILED.equals(result)) {
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,resultEntity.getMessage());
            return "member-reg";
        }

        String redisCode = resultEntity.getData();
        System.out.println("redisCode=" + redisCode);
        if (redisCode == null) {
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,FundingConstant.MESSAGE_CODE_NOT_EXISTS);
            return "member-reg";
        }

        // ?????????Redis???????????????value???????????????????????????Redis?????????
        String formCode = memberVO.getCode();
        System.out.println(formCode);
        if (!Objects.equals(formCode, redisCode)) {
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,FundingConstant.MESSAGE_CODE_INVALID);
            return "member-reg";
        } else {
            // ??????????????????????????????Redis??????
            redisRemoteService.removeRedisKeyRemote(key);
        }

        // ????????????
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String userpswdBeforeEncode = memberVO.getUserpswd();
        String userpswdAfterEncode = passwordEncoder.encode(userpswdBeforeEncode);
        memberVO.setUserpswd(userpswdAfterEncode);

        // ????????????
        // ????????????MemberPO??????
        MemberPO memberPO = new MemberPO();
        BeanUtils.copyProperties(memberVO,memberPO);
        ResultEntity<String> saveMemberResultEntity = mySQLRemoteService.saveMember(memberPO);
        if (ResultEntity.FAILED.equals(saveMemberResultEntity.getResult())){
            model.addAttribute(FundingConstant.ATTR_NAME_MESSAGE,saveMemberResultEntity.getMessage());
            return "member-reg";
        }
        // ??????????????????????????????????????????????????????????????????
        return "redirect:http://localhost/auth/member/to/login/page";
    }

    @ResponseBody
    @RequestMapping("/auth/member/send/short/message.json")
    public ResultEntity<String> sendMessage(@RequestParam("phone_number") String phone_number){
        // 1.??????????????????phoneNum??????
        ResultEntity<String> sendMessageResultEntity = FundingUtil.sendCodeByShortMessage(
                shortMessageProperties.getHost(),
                shortMessageProperties.getPath(),
                shortMessageProperties.getMethod(),
                shortMessageProperties.getAppcode(),
                shortMessageProperties.getTemplate_id(),
                phone_number);

        // 2.????????????????????????
        if (ResultEntity.SUCCESS.equals(sendMessageResultEntity.getResult())) {
            // 3.??????????????????????????????????????????Redis
            // ???????????????????????????????????????????????????????????????
            String code = sendMessageResultEntity.getData();
            // ????????????????????????Redis??????????????????key
            String key = FundingConstant.REDIS_CODE_PREFIX + phone_number;
            // ???????????????????????????Redis
            ResultEntity<String> saveCodeResultEntity = redisRemoteService.setRedisKeyValueRemoteWithTimeout(key, code, 15, TimeUnit.MINUTES);
            if (ResultEntity.SUCCESS.equals(saveCodeResultEntity.getResult())){
                return ResultEntity.successWithoutData();
            } else {
                return saveCodeResultEntity;
            }
        } else {
            return sendMessageResultEntity;
        }
    }
}
