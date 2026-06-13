#include <jni.h>
#include <vector>
#include <cstdlib>
#include <ctime>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "SnakeEngineC++"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct Point {
    int x;
    int y;

    bool operator==(const Point& other) const {
        return x == other.x && y == other.y;
    }
};

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_SnakeEngine_getEngineVersion(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF("C++ Retro Snake Engine v1.0 (Highly Optimized)");
}

JNIEXPORT jintArray JNICALL
Java_com_example_SnakeEngine_stepGame(
    JNIEnv *env,
    jobject thiz,
    jintArray snakeX,
    jintArray snakeY,
    jint direction,
    jint appleX,
    jint appleY,
    jint wallMode,
    jint gridWidth,
    jint gridHeight
) {
    // 1. Get snake arrays from Kotlin
    jsize len = env->GetArrayLength(snakeX);
    jint* xElems = env->GetIntArrayElements(snakeX, nullptr);
    jint* yElems = env->GetIntArrayElements(snakeY, nullptr);

    std::vector<Point> snake;
    for (int i = 0; i < len; ++i) {
        snake.push_back({xElems[i], yElems[i]});
    }

    env->ReleaseIntArrayElements(snakeX, xElems, JNI_ABORT);
    env->ReleaseIntArrayElements(snakeY, yElems, JNI_ABORT);

    // 2. Compute candidate head position
    Point head = snake.front();
    int nextX = head.x;
    int nextY = head.y;

    // Directions: 0 = UP, 1 = DOWN, 2 = LEFT, 3 = RIGHT
    if (direction == 0) {
        nextY -= 1;
    } else if (direction == 1) {
        nextY += 1;
    } else if (direction == 2) {
        nextX -= 1;
    } else if (direction == 3) {
        nextX += 1;
    }

    bool isDead = false;

    // 3. Boundary / Wall Collision check
    if (wallMode == 0) { // SOLID
        if (nextX < 0 || nextX >= gridWidth || nextY < 0 || nextY >= gridHeight) {
            isDead = true;
        }
    } else { // WRAP
        nextX = (nextX + gridWidth) % gridWidth;
        nextY = (nextY + gridHeight) % gridHeight;
    }

    Point nextPoint{nextX, nextY};

    // 4. Self-collision check
    if (!isDead) {
        for (const auto& segment : snake) {
            if (segment == nextPoint) {
                isDead = true;
                break;
            }
        }
    }

    int appleEaten = 0;
    int newAppleX = appleX;
    int newAppleY = appleY;
    std::vector<Point> newSnake;

    if (isDead) {
        // Keeps old snake and marks status as Game Over (status = 1)
        newSnake = snake;
    } else {
        newSnake.push_back(nextPoint); // Add new head
        if (nextPoint.x == appleX && nextPoint.y == appleY) {
            // Ate apple! Grow snake and generate new apple spot
            appleEaten = 1;
            for (const auto& segment : snake) {
                newSnake.push_back(segment);
            }

            // Simple random generator
            std::srand(std::time(nullptr));
            bool validApple = false;
            int attempts = 0;
            while (!validApple && attempts < 100) {
                int rX = std::rand() % gridWidth;
                int rY = std::rand() % gridHeight;
                Point p{rX, rY};

                bool insideSnake = false;
                for (const auto& segment : newSnake) {
                    if (segment == p) {
                        insideSnake = true;
                        break;
                    }
                }
                if (!insideSnake) {
                    newAppleX = rX;
                    newAppleY = rY;
                    validApple = true;
                }
                attempts++;
            }
            if (!validApple) {
                newAppleX = 5;
                newAppleY = 5;
            }
        } else {
            // Move: Add all parts except the last
            for (int i = 0; i < len - 1; ++i) {
                newSnake.push_back(snake[i]);
            }
        }
    }

    // 5. Pack results to return back to Compose/Kotlin
    // Pack format:
    // result[0]: gameStatus (0 = PLAYING, 1 = GAME_OVER)
    // result[1]: appleEaten (0 = NO, 1 = YES)
    // result[2]: newAppleX
    // result[3]: newAppleY
    // result[4...]: coordinates of new snake in [X0, Y0, X1, Y1, ...] format (so length is 4 + 2 * snakeSize)
    int resultStatus = isDead ? 1 : 0;
    int returnSize = 4 + 2 * newSnake.size();
    
    jintArray resultArray = env->NewIntArray(returnSize);
    std::vector<jint> packResults(returnSize);
    
    packResults[0] = resultStatus;
    packResults[1] = appleEaten;
    packResults[2] = newAppleX;
    packResults[3] = newAppleY;

    for (size_t i = 0; i < newSnake.size(); ++i) {
        packResults[4 + 2 * i] = newSnake[i].x;
        packResults[4 + 2 * i + 1] = newSnake[i].y;
    }

    env->SetIntArrayRegion(resultArray, 0, returnSize, packResults.data());
    return resultArray;
}

}
