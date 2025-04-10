package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.mapper.IVoucherOrderMapper;
import org.springframework.stereotype.Service;

/**
* @author cuiyq
* @description 针对表【tb_voucher_order】的数据库操作Service实现
* @createDate 2025-04-07 22:22:16
*/
@Service
public class IVoucherOrderServiceImpl extends ServiceImpl<IVoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {

    @Override
    public Result createVoucherOrder(Long voucherId) {
        return null;
    }
}




