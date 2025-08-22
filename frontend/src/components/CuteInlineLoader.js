import React from 'react';
import { Box, keyframes } from '@mui/material';
import { styled } from '@mui/material/styles';

// Keyframe animations for inline loader
const spin = keyframes`
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
`;

const pulse = keyframes`
  0%, 100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.1);
  }
`;

const bounce = keyframes`
  0%, 20%, 50%, 80%, 100% {
    transform: translateY(0);
  }
  40% {
    transform: translateY(-3px);
  }
  60% {
    transform: translateY(-1px);
  }
`;

// Styled components
const InlineContainer = styled(Box)(({ size }) => ({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: size === 'small' ? '0.8rem' : size === 'large' ? '1.5rem' : '1rem',
}));

const AnimatedEmoji = styled('span')(({ animation }) => {
  const animationMap = {
    spin: spin,
    pulse: pulse,
    bounce: bounce,
  };
  
  return {
    display: 'inline-block',
    animation: `${animationMap[animation] || bounce} 1s ease-in-out infinite`,
  };
});

const CuteInlineLoader = ({ 
  size = 'medium', 
  animation = 'bounce',
  emoji = '💰',
  className,
  ...props 
}) => {
  const emojis = ['💰', '💸', '💵', '🪙', '💳', '💎', '🏆', '⭐'];
  const randomEmoji = emojis[Math.floor(Math.random() * emojis.length)];
  const displayEmoji = emoji === '💰' ? randomEmoji : emoji;

  return (
    <InlineContainer size={size} className={className} {...props}>
      <AnimatedEmoji animation={animation}>
        {displayEmoji}
      </AnimatedEmoji>
    </InlineContainer>
  );
};

export default CuteInlineLoader;
