package com.tty.ari.dto.state.player;

import com.tty.api.state.State;
import lombok.Getter;
import org.bukkit.entity.Entity;

public class PlayerVanishState extends State {

    /**
     * 是否为玩家重新进入服务器时自动恢复的隐身（恢复时不发送伪造的退出信息）
     */
    @Getter
    private final boolean restored;

    public PlayerVanishState(Entity owner, boolean restored) {
        super(owner, Integer.MAX_VALUE);
        this.restored = restored;
    }

}
