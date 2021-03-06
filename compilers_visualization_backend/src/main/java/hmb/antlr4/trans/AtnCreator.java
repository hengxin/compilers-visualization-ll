package hmb.antlr4.trans;

import com.google.common.collect.ImmutableMap;
import hmb.protobuf.Response.*;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.StringTools;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.atn.*;

import java.util.*;

import static org.antlr.v4.runtime.atn.EpsilonTransition.epsilon;

public class AtnCreator {

    private static <T> T getOrDefault(Map<T, T> map, T t) {
        return map.getOrDefault(t, t);
    }

    public AtnCreator(Recognizer<?, ? extends ATNSimulator> recognizer) {
        this(recognizer, null);
    }

    public AtnCreator(Recognizer<?, ? extends ATNSimulator> recognizer, final Vocabulary lexerVocabulary) {
        final ATN atn = recognizer.getATN();
        final ATNState firstATNState = atn.states.get(0); // 第一个，主要是针对lexer的情景
        final ATNState lastATNState = atn.states.get(atn.states.size() - 1); // 最后一个，末尾
        final RuleStartState[] ruleToStartStates = atn.ruleToStartState;
        final var atnBuilder = AugmentedTransitionNetworks.newBuilder();

        final Map<ATNState, ATNState> mapBuilder = new HashMap<>();
        final Set<ATNState> allShowATNState = new HashSet<>();


        for (final RuleStartState RULE_START_STATE : ruleToStartStates) {
            Map<ATNState, ATNState> merged = AtnMerger.merge(RULE_START_STATE);

            // print sub ATN
            final var subAtnBuilder = SubAugmentedTransitionNetwork.newBuilder();
            final ATNState ruleStartState = getOrDefault(merged, RULE_START_STATE);  // 一般都是真正startState的下一个，用于遍历
            final ATNState ruleStopState = RULE_START_STATE.stopState;

            mapBuilder.put(ruleStartState, RULE_START_STATE);
            merged.forEach((k, v) -> {
                if (v.equals(ruleStartState)) {
                    v = RULE_START_STATE;
                }
                mapBuilder.put(k, v);
            });

//            System.out.println(ruleStartState.ruleIndex + ": " + recognizer.getRuleNames()[ruleStartState.ruleIndex] + ':');
//            System.out.println();

            LinkedList<ATNState> atnStateList = new LinkedList<>();  // 用于bfs，判断循环何时退出
            atnStateList.add(ruleStartState);

            Set<Transition> visitedTransitionSet = new HashSet<>();

            Map<ATNState, Set<ATNState>> sourceToTarget = new HashMap<>();
            Map<ATNState, Set<ATNState>> targetToSource = new HashMap<>();
            List<Triple> tripleList = new ArrayList<>();

            while (!atnStateList.isEmpty()) {
                final ATNState currentState = atnStateList.removeFirst();
                // map ruleStartState to RULE_START_STATE, 用于展示
                final ATNState source = ruleStartState.equals(currentState) ? RULE_START_STATE : currentState;

                for (final Transition transition : currentState.getTransitions()) {
                    final ATNState nextState = getOrDefault(merged, Antlr4Utils.nextATNState(transition));
                    final ATNState target = ruleStartState.equals(nextState) ? RULE_START_STATE : nextState;
                    if (!visitedTransitionSet.contains(transition)) {
                        sourceToTarget.putIfAbsent(source, new HashSet<>());
                        sourceToTarget.get(source).add(target);
                        targetToSource.putIfAbsent(target, new HashSet<>());
                        targetToSource.get(target).add(source);
                        tripleList.add(new Triple(source, transition, target));
                        visitedTransitionSet.add(transition);
                        if (!nextState.equals(ruleStopState)) {
                            atnStateList.add(nextState);
                        }
                    }
                }
            }

            {
                for (Triple triple1 : tripleList) {
                    if (triple1.first().equals(triple1.second())) {
                        throw new RuntimeException("self loop");
                    }
                }


                Set<ATNState> addedAtnNode = new HashSet<>();  // 用于展示

                for (Triple triple : tripleList) {
                    Transition transition = triple.transition();
                    String transitionString = switch (transition) {
                        case RuleTransition rule -> recognizer.getRuleNames()[rule.ruleIndex];
                        case AtomTransition atom && lexerVocabulary != null -> lexerVocabulary.getSymbolicName(atom.label);
                        case ActionTransition action && atn.grammarType == ATNType.LEXER -> atn.lexerActions[action.actionIndex].toString();
                        case default -> StringTools.replace(transition.toString());
                        case null -> throw new RuntimeException("transition");
                    };

                    String lineStyleColor = switch (transition) {
                        case RuleTransition ignore -> "rgba(255,0,0,0.5)";
                        case EpsilonTransition ignore -> "rgba(0,0,0,0.45)";
                        case ActionTransition ignore -> "rgba(0,0,0,0.44)";
                        case Object ignore && epsilon.equals(transitionString) -> "rgba(0,0,0,0.46)";
                        case default -> "rgba(0,128,0,0.66)";
                    };


                    // 如果 A--t1-->B, B--t2-->A，则这两段都需要弯曲
                    final boolean isLoop = sourceToTarget.getOrDefault(triple.second(), Collections.emptySet()).contains(triple.first());

                    allShowATNState.add(triple.first());
                    allShowATNState.add(triple.second());
                    if (triple.first().equals(lastATNState) || triple.second().equals(lastATNState)) {
                        throw new RuntimeException("match last AtnState");
                    }

                    if (addedAtnNode.add(triple.first())) {
                        subAtnBuilder.addGraphNode(
                                AtnNode.newBuilder()
                                        .setId(String.valueOf(triple.first().stateNumber))
                                        .setName(triple.first().toString())
                                        .setX(triple.first().equals(RULE_START_STATE) ? 0 : 500)
                                        .setY(triple.first().equals(RULE_START_STATE) ? 700 : 600)
                                        .setFixed(triple.first().equals(RULE_START_STATE))
                                        .setItemStyle(ItemStyle.newBuilder().build())
                                        .build());
                    }

                    if (addedAtnNode.add(triple.second())) {
                        subAtnBuilder.addGraphNode(
                                AtnNode.newBuilder()
                                        .setId(String.valueOf(triple.second().stateNumber))
                                        .setName(triple.second().toString())
                                        .setX(triple.second().equals(ruleStopState) ? 1000 : 500)
                                        .setY(triple.second().equals(ruleStopState) ? 700 : 600)
                                        .setFixed(triple.second().equals(ruleStopState))
                                        .setItemStyle(ItemStyle.newBuilder().build())
                                        .build());
                    }

                    subAtnBuilder.addGraphEdge(
                            AtnLink.newBuilder()
                                    .setName(transitionString)
                                    .setSource(String.valueOf(triple.first().stateNumber))
                                    .setTarget(String.valueOf(triple.second().stateNumber))
                                    .setLabel(Label.newBuilder()
                                            .setShow(Label.getDefaultInstance().getShow())
//                                            .setFormatter(transitionString)
                                            .setLineHeight(Label.getDefaultInstance().getLineHeight())
                                            .build())
                                    .setLineStyle(LineStyle.newBuilder()
                                            .setColor(lineStyleColor)
                                            .build())
                                    .build()
                    );
//                    System.out.println(triple.first() + " --    " + transitionString + "    --> " + triple.second());
                }
            }

            atnBuilder.addSubATN(subAtnBuilder.setRuleName(recognizer.getRuleNames()[ruleStartState.ruleIndex]).build());

//            System.out.println();
//            System.out.println("\n+===============================================================================+\n");
        }
        this.augmentedTransitionNetworks = atnBuilder.build();
        this.mapper = ImmutableMap.copyOf(mapBuilder);

        List<ATNState> states = atn.states;
        for (ATNState state : states) {
            // lexer 模式应该跳过第一个，因为它是辅助的首状态
            if (state.equals(lastATNState) || (lexerVocabulary == null && state.equals(firstATNState))) {
                continue;
            }
            state = this.mapper.getOrDefault(state, state);
            if (!allShowATNState.contains(state)) {
                throw new RuntimeException("state " + state + " not exist");
            }
        }

    }


    private final AugmentedTransitionNetworks augmentedTransitionNetworks;
    private final ImmutableMap<ATNState, ATNState> mapper;

    public AugmentedTransitionNetworks getATNs() {
        return augmentedTransitionNetworks;
    }

    public ImmutableMap<ATNState, ATNState> getMapper() {
        return mapper;
    }
}
