package com.example.openrouter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
class OpenrouterApplicationTests {

    static AtomicInteger state = new AtomicInteger(0);

    public static void main(String[] args) {
        a().block();
    }

//    static Mono<Void> a() {
//        return b()
//                .doFinally(signal -> {
//                    try {
//                        Thread.sleep(10000);
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                    System.out.println("A doFinally start, state = " + state.get());
//                    System.out.println("A doFinally end");
//                });
//    }
//
//    static Mono<Void> b() {
//        return Mono.<Void>fromRunnable(() -> {
//                    System.out.println("B executing");
//                })
//                .doFinally(signal -> {
//                    try {
//                        System.out.println("B doFinally start (sleeping)...");
//                        state.set(1);
//                        System.out.println("B doFinally set state = 1");
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                });
//    }

    static Mono<Void> a() {
        return b()
                .doOnError(e -> {
                    System.out.println("A doOnError");
                });
    }

    static Mono<Void> b() {
        return Mono.<Void>error(new RuntimeException("boom"))
                .doOnError(e -> {
                    System.out.println("B doOnError");
                });
    }

}
